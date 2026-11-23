#!/usr/bin/env python3
"""Exercise inbound Kit MCP/ACP/A2A with a native CLI and isolated, local GraphQL fixtures."""
import argparse
import copy
import json
import os
from pathlib import Path
import select
import socket
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen

SESSION_ID = '11111111-1111-1111-1111-111111111111'
TURN_ID = '33333333-3333-3333-3333-333333333333'
EVENT = json.loads((Path(__file__).resolve().parents[1] / 'src/test/resources/kit/harness-turn.json').read_text())


class Fixture:
    def __init__(self, cancellation=False, outcome='COMPLETED'):
        self.sent = False
        self.polls = 0
        self.cancellation = cancellation
        self.outcome = outcome
        self.cancel_requested = False
        self.cancel_polls = 0
        self.operations = []
        owner = self

        class GraphQL(BaseHTTPRequestHandler):
            def do_POST(self):
                request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                operation = request['operationName']
                owner.operations.append(operation)
                assert self.headers.get('Authorization') == 'Bearer bsk_inbound_fixture'
                if operation == 'CreateKitChatSession':
                    owner.sent, owner.polls = False, 0
                    value = {'create': owner.session()}
                elif operation == 'GetKitChatSession':
                    value = {'session': owner.session()}
                elif operation == 'SendKitChatMessage':
                    owner.sent, owner.polls = True, 0
                    value = {'send': True}
                elif operation == 'CancelKitChatSession':
                    owner.cancel_requested = True
                    value = {'cancel': True}
                else:
                    raise AssertionError(f'Unexpected API operation: {operation}')
                body = json.dumps({'data': {'chatSessions': value}}).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_):
                pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), GraphQL)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def session(self):
        messages = []
        if self.sent:
            self.polls += 1
            event = copy.deepcopy(EVENT)
            if self.cancel_requested:
                self.cancel_polls += 1
            terminal = self.cancel_polls >= 2 if self.cancellation else self.polls > 1
            event['status'] = self.outcome if terminal else 'STREAMING'
            messages.append({'id': TURN_ID, 'sessionId': SESSION_ID, 'author': 'kit',
                             'created': '2026-10-23T00:00:00Z', 'event': event})
        return {'id': SESSION_ID, 'agentKey': 'kit', 'title': 'Rich results', 'messages': messages,
                'processing': self.sent, 'status': 'STREAMING' if self.sent else 'COMPLETED',
                'created': '2026-10-23T00:00:00Z', 'modified': '2026-10-23T00:00:00Z'}

    @property
    def url(self):
        return f'http://127.0.0.1:{self.server.server_port}/graphql'

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


def send(process, method, params, identifier=None):
    message = {'jsonrpc': '2.0', 'method': method, 'params': params}
    if identifier is not None:
        message['id'] = identifier
    process.stdin.write((json.dumps(message) + '\n').encode())
    process.stdin.flush()


def rpc(process, identifier, method, params):
    send(process, method, params, identifier)
    return receive(process, identifier)


def receive(process, identifier):
    notifications = []
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if not select.select([process.stdout], [], [], max(0, deadline - time.monotonic()))[0]:
            break
        line = process.stdout.readline()
        if not line:
            raise RuntimeError(f'CLI exited waiting for request {identifier}')
        response = json.loads(line)
        if response.get('id') == identifier:
            assert 'error' not in response, response
            return response['result'], notifications
        notifications.append(response)
    raise TimeoutError(f'No response for request {identifier}')


def wait_for(predicate):
    deadline = time.monotonic() + 15
    while not predicate():
        assert time.monotonic() < deadline, 'Timed out waiting for fixture activity'
        time.sleep(0.01)


def check_mcp(process, fixture):
    rpc(process, 1, 'initialize', {'protocolVersion': '2025-11-25', 'capabilities': {},
                                 'clientInfo': {'name': 'kit-native-smoke', 'version': '1'}})
    process.stdin.write(b'{"jsonrpc":"2.0","method":"notifications/initialized"}\n')
    process.stdin.flush()
    if fixture.cancellation:
        send(process, 'tools/call', {'name': 'kit_chat', 'arguments': {'action': 'ask', 'message': 'Report'}}, 2)
        wait_for(lambda: fixture.sent and fixture.polls >= 1)
        send(process, 'notifications/cancelled', {'requestId': 999})
        time.sleep(0.1)
        assert not fixture.cancel_requested
        for _ in range(2):
            send(process, 'notifications/cancelled', {'requestId': 2})
        wait_for(lambda: fixture.cancel_polls >= 2)
        history, notifications = rpc(process, 3, 'tools/call', {'name': 'kit_chat', 'arguments': {'action': 'get', 'sessionId': SESSION_ID}})
        assert all(item.get('id') != 2 for item in notifications), notifications
        saved = history['structuredContent']['session']['messages'][0]['event']
        assert saved['status'] == fixture.outcome, saved
        send(process, 'notifications/cancelled', {'requestId': 2})
        rpc(process, 4, 'ping', {})
        return
    result, _ = rpc(process, 2, 'tools/call', {'name': 'kit_chat', 'arguments': {'action': 'ask', 'message': 'Report'}})
    assert not result.get('isError'), result
    content = result['content']
    assert {'text', 'image', 'audio', 'resource_link', 'resource'} <= {item['type'] for item in content}
    image = next(item for item in content if item['type'] == 'image')
    assert image['data'] == 'aGVsbG8=' and image['_meta']['boscaToolCallId'] == f'{TURN_ID}/result'
    assert result['structuredContent']['harnessTurns'][0]['status'] == 'COMPLETED'
    assert 'private-' not in json.dumps(result)
    history, _ = rpc(process, 3, 'tools/call', {'name': 'kit_chat', 'arguments': {'action': 'get', 'sessionId': SESSION_ID}})
    saved = json.loads(history['content'][0]['text'])['session']['messages'][0]['event']
    assert saved['type'] == 'harness-turn' and saved['content']
    assert 'private-' not in json.dumps(saved)


def check_acp(process, fixture):
    rpc(process, 1, 'initialize', {'protocolVersion': 1, 'clientCapabilities': {},
                                 'clientInfo': {'name': 'kit-native-smoke', 'version': '1'}})
    session, _ = rpc(process, 2, 'session/new', {'cwd': '/tmp', 'mcpServers': []})
    params = {'sessionId': session['sessionId'], 'prompt': [{'type': 'text', 'text': 'Report'}]}
    if fixture.cancellation:
        send(process, 'session/prompt', params, 3)
        wait_for(lambda: fixture.sent and fixture.polls >= 1)
        send(process, 'session/cancel', {'sessionId': session['sessionId']})
        result, notifications = receive(process, 3)
    else:
        result, notifications = rpc(process, 3, 'session/prompt', params)
    expected = {'COMPLETED': 'end_turn', 'CANCELLED': 'cancelled', 'INTERRUPTED': 'refusal'}[fixture.outcome]
    assert result['stopReason'] == expected, result
    updates = [item['params']['update'] for item in notifications if item.get('method') == 'session/update']
    assert any(item['sessionUpdate'] == 'plan' for item in updates)
    tool = next(item for item in updates if item['sessionUpdate'] == 'tool_call')
    assert tool['toolCallId'] == f'{TURN_ID}/result' and len(tool['content']) == 4
    assert tool['content'][0]['content']['type'] == 'resource'
    assert tool['content'][1]['content']['type'] == 'image'
    assert tool['content'][-1]['type'] == 'diff'
    assert 'private-' not in json.dumps(updates)


def check_a2a(process, port, fixture):
    base = f'http://127.0.0.1:{port}'
    deadline = time.monotonic() + 20
    while True:
        try:
            with urlopen(base + '/.well-known/agent-card.json', timeout=1) as response:
                assert json.load(response)['name'] == 'Kit'
            break
        except OSError:
            if process.poll() is not None or time.monotonic() >= deadline:
                raise
            time.sleep(0.05)

    def request(method, params, allow_error=False):
        body = json.dumps({'jsonrpc': '2.0', 'id': 'test', 'method': method, 'params': params}).encode()
        with urlopen(Request(base + '/a2a/kit', data=body, headers={'Content-Type': 'application/json'}), timeout=20) as response:
            result = json.load(response)
            if allow_error:
                return result
            assert 'error' not in result, result
            return result['result']

    task = request('message/send', {'configuration': {'blocking': not fixture.cancellation}, 'message': {'kind': 'message',
                   'messageId': 'report', 'role': 'user', 'parts': [{'kind': 'text', 'text': 'Report'}]}})
    if fixture.cancellation:
        wait_for(lambda: fixture.sent and fixture.polls >= 1)
        result = request('tasks/cancel', {'id': task['id']}, allow_error=True)
        assert ('error' in result) == (fixture.outcome != 'CANCELLED'), result
        task = request('tasks/get', {'id': task['id']})
        assert task['status']['state'] == {'COMPLETED': 'completed', 'CANCELLED': 'canceled', 'INTERRUPTED': 'failed'}[fixture.outcome], task
        if fixture.outcome == 'CANCELLED':
            assert result['result']['artifacts'][0]['parts'][0]['data']['status'] == 'CANCELLED'
        return
    deadline = time.monotonic() + 20
    while task['status']['state'] not in ('completed', 'failed', 'canceled'):
        assert time.monotonic() < deadline, task
        time.sleep(0.05)
        task = request('tasks/get', {'id': task['id']})
    assert task['status']['state'] == 'completed', task
    artifact, = task['artifacts']
    assert artifact['artifactId'] == TURN_ID
    assert len([part for part in artifact['parts'] if part['kind'] == 'file']) == 3
    assert artifact['parts'][0]['data']['status'] == 'COMPLETED'
    assert 'private-' not in json.dumps(task)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('binary', type=Path)
    parser.add_argument('--cancellation', action='store_true', help='Request cancellation and check confirmed/racing terminal outcomes')
    parser.add_argument('--outcome', choices=['COMPLETED', 'CANCELLED', 'INTERRUPTED'], default='CANCELLED')
    args = parser.parse_args()
    for protocol, check in [('mcp-server', check_mcp), ('acp-server', check_acp), ('a2a-server', check_a2a)]:
        fixture = Fixture(args.cancellation, args.outcome if args.cancellation else 'COMPLETED')
        try:
            with tempfile.TemporaryDirectory(prefix='kit-native-protocol-') as temporary:
                directory = Path(temporary)
                env = {key: value for key, value in os.environ.items() if not key.startswith('BOSCA_')}
                env.update(BOSCA_TOKEN='bsk_inbound_fixture', BOSCA_ENDPOINT=fixture.url, BOSCA_NO_UPDATE_CHECK='1',
                           XDG_CONFIG_HOME=str(directory / 'config'))
                command = [str(args.binary.resolve()), protocol]
                port = None
                if protocol == 'a2a-server':
                    with socket.socket() as listener:
                        listener.bind(('127.0.0.1', 0))
                        port = listener.getsockname()[1]
                    command += ['--port', str(port)]
                diagnostic_path = directory / 'stderr.log'
                with diagnostic_path.open('wb') as diagnostics:
                    process = subprocess.Popen(command, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                               stderr=diagnostics, bufsize=0)
                    try:
                        check(process, port, fixture) if port else check(process, fixture)
                        if args.cancellation:
                            assert fixture.operations.count('CancelKitChatSession') == 1, fixture.operations
                            assert fixture.operations.count('SendKitChatMessage') == 1, fixture.operations
                        else:
                            assert fixture.polls == (3 if protocol == 'mcp-server' else 2), fixture.operations
                        assert all(op in ('CreateKitChatSession', 'GetKitChatSession', 'SendKitChatMessage', 'CancelKitChatSession') for op in fixture.operations)
                        assert not (directory / 'config/bosca/config.json').exists()
                    except Exception as error:
                        raise RuntimeError(f'{protocol}: {diagnostic_path.read_text()[-5000:]}') from error
                    finally:
                        process.terminate()
                        try:
                            process.communicate(timeout=5)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.communicate(timeout=5)
                print(f'{protocol}: typed results, native serialization, scoped token and outcome {fixture.outcome} passed; cancellation={args.cancellation}')
        finally:
            fixture.close()


if __name__ == '__main__':
    main()
