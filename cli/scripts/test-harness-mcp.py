#!/usr/bin/env python3
"""Exercise a built native CLI against local MCP and GraphQL fixtures, without saved credentials."""
import argparse
import base64
import json
import os
from pathlib import Path
import select
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("binary", type=Path)
    parser.add_argument("--launcher-environment", type=Path,
                        help="Write an isolated runtime launcher's environment fixture instead of passing CLI auth options")
    args = parser.parse_args()
    credentials = []
    operations = []
    variables = []
    state = {"revoked": False, "scripture_denied": False, "image_preview_denied": False}
    conversation_id = "12345678-1234-1234-1234-123456789012"
    memory_id = "12345678-1234-1234-1234-123456789013"
    memory = {"id": memory_id, "version": 1, "revision": "memory-revision", "statement": "Prefer concise answers",
              "sourceChanged": False, "tags": ["writing"], "created": "2026-10-22T00:00:00Z", "modified": None,
              "provenance": {"scope": "PERSONAL", "ownerId": "12345678-1234-1234-1234-123456789014",
                             "collectionId": None, "sourceMetadataId": None, "sourceVersion": None,
                             "sourceRevision": None, "conversationId": conversation_id, "expires": None}}
    image_bytes = b"native-image-fixture"
    image_downloads = []
    stored_image = {"id": memory_id, "version": 1, "name": "Bird", "uploaded": "2026-10-24T00:00:00Z",
                    "content": {"type": "image/png", "length": len(image_bytes)}}
    stored_document = {"title": "Saved report", "template": None, "content": {"document": {"type": "doc", "content": []}}}
    memory_operations = {"RememberMemory": "remember", "GetMemory": "get", "SearchMemories": "search",
                         "UpdateMemory": "update", "ForgetMemory": "forget"}
    bible = {"metadataId": memory_id, "version": 2, "variant": "reader", "defaultVariant": True,
             "name": "Fixture Bible", "nameLocal": "Fixture Bible", "abbreviation": "FB", "abbreviationLocal": "FB"}
    reference = {"usfm": "JHN.3.16", "human": "John 3:16", "humanShort": "Jn 3:16"}
    passage = {"reference": reference, "component": [{"_": "t", "text": "Exact fixture text — selected verse.", "style": None}],
               "attribution": {"copyright": "Fixture copyright", "citation": "Fixture citation", "informationUrl": None}}

    script = {"id": memory_id, "key": "fixture", "name": "Fixture", "description": "Keep this",
              "source": "main { null }", "type": "GENERAL", "version": 3, "enabled": True,
              "public": False, "inputSchema": None, "outputSchema": None, "configuration": {"keep": True}}
    script_operations = {"ListManagedScripts": ("all", [script]), "GetManagedScript": ("script", script),
                         "GetManagedScriptByKey": ("scriptByKey", script), "AddManagedScript": ("addScript", script),
                         "EditManagedScript": ("editScript", script), "EnableManagedScript": ("enableScript", True),
                         "DisableManagedScript": ("disableScript", True), "DeleteManagedScript": ("deleteScript", True),
                         "ExecuteManagedScript": ("executeScript", [1, "result", None]),
                         "ValidateManagedScript": ("validateScript", {"valid": True, "compiled": True, "errors": []})}

    pipeline_graph = {"nodes": [{"id": "in", "type": "input", "acceptedType": "JSON", "position": {"x": 0, "y": 0}}], "edges": []}
    pipeline = {"id": memory_id, "key": "fixture", "name": "Fixture", "description": "Keep this", "acceptedInputType": "JSON",
                "tags": ["release"], "version": 3, "graph": pipeline_graph, "triggered": False, "api": False, "public": False,
                "schedule": None, "maxConcurrentRuns": 2, "maxRunsPerMinute": 4, "gitRepositoryId": None, "gitPath": None,
                "lastSyncError": None, "inputSchema": None, "outputType": "JSON", "outputSchema": None, "hasOutput": True}
    pipeline_run = {"id": conversation_id, "pipelineId": memory_id, "status": "OK", "eventName": "manual", "input": [1, 2],
                    "output": {"result": 7}, "error": None, "createdAt": "2026-10-24T00:00:00Z", "modifiedAt": "2026-10-24T00:01:00Z",
                    "awaitingNodes": [], "completedNodeIds": ["in"], "nodes": [], "steps": []}
    pipeline_operations = {"ListManagedPipelines": ("all", [pipeline]), "GetManagedPipeline": ("pipeline", pipeline),
                          "GetManagedPipelineByKey": ("pipelineByKey", pipeline), "SaveManagedPipeline": ("save", pipeline),
                          "ValidateManagedPipeline": ("validateGraph", {"valid": True, "errors": [], "graph": pipeline_graph}),
                          "DryRunManagedPipeline": ("dryRun", {"outputs": {}, "actions": {}, "errors": {}, "skipped": {}, "error": None}),
                          "RunManagedPipeline": ("run", {"ok": True, "runId": conversation_id, "status": "SUSPENDED", "output": None, "error": None}),
                          "GetManagedPipelineRun": ("run", pipeline_run)}

    class GraphQL(BaseHTTPRequestHandler):
        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            operation = request.get("operationName")
            operations.append(operation)
            variables.append(request.get("variables", {}))
            credentials.append(self.headers.get("Authorization"))
            status = 200
            response = {}
            if state["revoked"]:
                status = 401
            elif operation == "ListAnalyticsQueries":
                response = {"data": {"analytics": {"queries": {"all": []}}}}
            elif operation == "ExecuteAnalyticsQueryByKey":
                response = {"data": {"analytics": {"queries": {"executeByKey": {
                    "records": [{"day": "Monday", "sessions": 12}, {"day": "Tuesday", "sessions": 18}],
                    "cached": True, "stale": True, "refreshedAt": None,
                    "evidence": {"queryId": "11111111-1111-1111-1111-111111111111", "sql": "SELECT day, sessions FROM traffic WHERE country = ?",
                        "parameters": [{"position": 1, "parameter": "country", "type": "STRING", "value": "US"}],
                        "executedAt": "2026-10-23T12:00:00Z", "durationMs": 17}}}}}}
            elif operation == "ListAnalyticsCatalogs":
                response = {"data": {"analytics": {"warehouse": {"catalogs": ["warehouse"]}}}}
            elif operation == "ListAnalyticsSchemas":
                response = {"data": {"analytics": {"warehouse": {"schemas": ["bosca"]}}}}
            elif operation == "ListAnalyticsTables":
                response = {"data": {"analytics": {"warehouse": {"tables": ["events"]}}}}
            elif operation == "DescribeAnalyticsTable":
                response = {"data": {"analytics": {"warehouse": {"describe": [{"name": "sessions", "typeName": "bigint", "nullable": False}]}}}}
            elif operation == "ExecuteAnalyticsSql":
                response = {"data": {"analytics": {"warehouse": {"execute": {
                    "sql": request["variables"]["sql"], "columns": [{"name": "sessions", "typeName": "bigint", "nullable": False}],
                    "records": [{"sessions": 42}], "truncated": True, "executedAt": "2026-10-24T00:00:00Z", "durationMs": 7}}}}}
            elif operation in ("GenerateContentImage", "EditContentImage"):
                field = "generate" if operation == "GenerateContentImage" else "edit"
                response = {"data": {"content": {"images": {field: stored_image}}}}
            elif operation == "GetContentImage":
                response = {"data": {"content": {"metadata": stored_image}}}
            elif operation == "AttachContentImage":
                attribute = {"key": "hero", "name": "Hero", "description": "Cover image", "relationship": "image.featured",
                             "list": False, "targetSize": [740, 416], "defaultAspectRatio": 16 / 9}
                attachment = {"success": True, "attached": True, "targetLanguageTag": None, "attribute": attribute,
                              "availableAttributes": [attribute], "crop": request["variables"]["crop"],
                              "collaborationUpdated": True, "optimized": False,
                              "warning": "Image attached; optimization unavailable", "error": None}
                response = {"data": {"content": {"images": {"attach": attachment}}}}
            elif operation == "GetMetadataDownload":
                response = ({"errors": [{"message": "preview denied"}]} if state["image_preview_denied"] else
                            {"data": {"content": {"metadata": {"content": {**stored_image["content"], "urls": {"download": {
                                "url": f"http://127.0.0.1:{server.server_port}/image", "headers": [{"name": "X-Image-Signature", "value": "fixture"}]}}}}}}})
            elif operation == "GetMetadataDocument":
                response = {"data": {"content": {"metadata": {"document": stored_document}}}}
            elif operation == "AddMetadata":
                document = request["variables"]["metadata"]["document"]
                stored_document.update(title=document["title"], content=document["content"])
                stored_document["template"] = {"id": document["templateMetadataId"], "version": document["templateMetadataVersion"]}
                response = {"data": {"content": {"metadata": {"add": {"id": memory_id}}}}}
            elif operation == "GetMetadata":
                metadata = {"id": memory_id, "slug": "article", "version": 1, "locked": False, "parentId": None,
                    "name": "Article", "type": "STANDARD", "content": {"type": "bosca/v-document", "length": None},
                    "labels": [], "languageTag": "en", "public": False, "publicContent": False, "publicSupplementary": False,
                    "created": "2026-10-25T00:00:00Z", "modified": "2026-10-25T00:00:00Z", "uploaded": None, "ready": None,
                    "attributes": {}, "systemAttributes": {}, "traitIds": [], "source": None,
                    "metadataWorkflow": {"state": "pending", "stateValid": None, "pending": None}, "supplementary": [], "categories": []}
                response = {"data": {"content": {"metadata": metadata}}}
            elif operation == "SetDocument":
                document = request["variables"]["document"]
                stored_document.update(title=document["title"], content=document["content"])
                response = {"data": {"content": {"metadata": {"setMetadataDocument": True}}}}
            elif operation == "SetMetadataReady":
                response = {"data": {"content": {"metadata": {"setMetadataReady": True}}}}
            elif operation == "DeleteMetadata":
                response = {"data": {"content": {"metadata": {"delete": True}}}}
            elif operation in memory_operations:
                value = [memory] if operation == "SearchMemories" else True if operation == "ForgetMemory" else memory
                response = {"data": {"ai": {"memories": {memory_operations[operation]: value}}}}
            elif operation == "GetPipelineCatalog":
                response = {"data": {"pipelines": {"types": ["JSON"], "shapes": [], "nodeTypes": []}}}
            elif operation in pipeline_operations:
                field, value = pipeline_operations[operation]
                response = {"data": {"pipelines": {field: value}}}
            elif operation in script_operations:
                field, value = script_operations[operation]
                response = {"data": {"scripts": {field: value}}}
            elif operation == "ListScriptureBibles":
                response = {"data": {"content": {"findMetadata": [{"id": memory_id, "bibles": [bible]}]}}}
            elif operation == "GetScriptureBooks":
                response = {"data": {"content": {"metadata": {"bible": {**bible, "books": [{
                    "nameLong": "John", "nameShort": "John", "abbreviation": "Jn", "reference": {"usfm": "JHN"},
                    "chapters": [{"reference": {"usfm": "JHN.3", "human": "John 3"}}]}]}}}}}
            elif operation == "ResolveScriptureReferences":
                response = {"data": {"content": {"metadata": {"bible": {**bible, "references": [reference]}}}}}
            elif operation == "GetScriptureVerses":
                response = ({"errors": [{"message": "AI_CONTEXT denied"}]} if state["scripture_denied"] else
                            {"data": {"content": {"metadata": {"bible": {**bible, "aiContext": [passage]}}}}})
            else:
                status = 400
            body = json.dumps(response).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            image_downloads.append((self.headers.get("Authorization"), self.headers.get("X-Image-Signature")))
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(image_bytes)))
            self.end_headers()
            self.wfile.write(image_bytes)

        def log_message(self, *_):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), GraphQL)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix="bosca-mcp-native-") as temporary:
            directory = Path(temporary)
            env = {key: value for key, value in os.environ.items() if not key.startswith("BOSCA_")}
            env.update(BOSCA_NO_UPDATE_CHECK="1", BOSCA_TOKEN="bsk_native_runtime",
                       BOSCA_MEMORY_SESSION_ID=conversation_id,
                       XDG_CONFIG_HOME=str(directory / "config"))
            command = [
                str(args.binary.resolve()), "mcp-server",
                "--url", f"http://127.0.0.1:{server.server_port}/graphql",
            ]
            if args.launcher_environment:
                args.launcher_environment.parent.mkdir(parents=True, exist_ok=True)
                args.launcher_environment.write_text(json.dumps({"BOSCA_TOKEN": "bsk_native_runtime",
                    "BOSCA_ENDPOINT": f"http://127.0.0.1:{server.server_port}/graphql", "BOSCA_NO_UPDATE_CHECK": "1",
                    "BOSCA_MEMORY_SESSION_ID": conversation_id}))
                args.launcher_environment.chmod(0o600)
                env.pop("BOSCA_TOKEN", None)
                env.pop("BOSCA_ENDPOINT", None)
                env.pop("BOSCA_MEMORY_SESSION_ID", None)
                command = [str(args.binary.resolve())]
            diagnostic_path = directory / 'stderr.log'
            diagnostics = diagnostic_path.open('wb')
            process = subprocess.Popen(command, env=env, stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=diagnostics, bufsize=0)

            def request(identifier, method, params):
                process.stdin.write((json.dumps({
                    "jsonrpc": "2.0", "id": identifier, "method": method, "params": params,
                }) + "\n").encode())
                process.stdin.flush()
                while True:
                    ready, _, _ = select.select([process.stdout], [], [], 15)
                    if not ready:
                        raise TimeoutError(f"No response for {method}: {diagnostic_path.read_text()[-4000:]}")
                    line = process.stdout.readline()
                    if not line:
                        raise RuntimeError("Native MCP server exited before responding")
                    response = json.loads(line)
                    if response.get("id") == identifier:
                        return response

            def call(identifier, name, arguments=None):
                return request(identifier, "tools/call", {"name": name, "arguments": arguments or {}})

            def failed(response):
                return "error" in response or response.get("result", {}).get("isError") is True

            try:
                initialized = request(1, "initialize", {
                    "protocolVersion": "2025-11-25", "capabilities": {},
                    "clientInfo": {"name": "bosca-native-smoke", "version": "1"},
                })
                assert "result" in initialized, initialized
                process.stdin.write(b'{"jsonrpc":"2.0","method":"notifications/initialized"}\n')
                process.stdin.flush()
                listed = request(2, "tools/list", {})["result"]["tools"]
                names = {tool["name"] for tool in listed}
                assert {"workops_spec_instructions", "analytics_query", "content_metadata", "kit_chat", "bosca_memory", "content_scripture", "bosca_script", "bosca_pipeline", "analytics_sql", "content_image"} <= names
                query = next(tool for tool in listed if tool["name"] == "analytics_query")
                actions = set(query["inputSchema"]["properties"]["action"]["enum"])
                assert {"list", "execute", "create", "grant_permission"} <= actions
                assert not failed(call(3, "workops_spec_instructions"))
                assert not failed(call(4, "analytics_query", {"action": "list"}))
                assert not failed(call(5, "analytics_query", {"action": "list"}))
                assert credentials == ["Bearer bsk_native_runtime"] * 2
                assert operations == ["ListAnalyticsQueries"] * 2
                for identifier, action in enumerate(["remember", "remember", "get", "search", "update", "forget"], 10):
                    reply = call(identifier, "bosca_memory", {
                        "action": action, "requestId": "12345678-1234-1234-1234-123456789015",
                        "id": memory_id, "statement": memory["statement"], "expectedRevision": memory["revision"],
                        "tags": ["writing"], "conversationId": "12345678-1234-1234-1234-123456789099",
                    })
                    assert not failed(reply), reply
                    text = next(item["text"] for item in reply["result"]["content"] if item["type"] == "text")
                    decoded = json.loads(text)
                    assert "bsk_native_runtime" not in text
                    if action == "forget":
                        assert decoded == {"forgotten": True}
                    else:
                        result = decoded[0] if action == "search" else decoded
                        assert result["id"] == memory_id and result["provenance"]["scope"] == "PERSONAL"
                assert variables[2]["input"] == variables[3]["input"], "Retry input changed"
                assert variables[2]["input"]["conversationId"] == conversation_id
                assert all(variables[index]["conversationId"] == conversation_id for index in [4, 5, 6])
                assert all(variables[index]["expectedRevision"] == memory["revision"] for index in [6, 7])
                expected_operations = ["ListAnalyticsQueries"] * 2 + [
                    "RememberMemory", "RememberMemory", "GetMemory", "SearchMemories", "UpdateMemory", "ForgetMemory"]
                assert operations == expected_operations
                reply = call(20, "analytics_query", {"action": "execute", "key": "metrics /?#✓", "limit": 1})
                assert not failed(reply), reply
                resource = next(item["resource"] for item in reply["result"]["content"] if item["type"] == "resource")
                assert resource["uri"] == "bosca://analytics/queries/by-key/metrics%20%2F%3F%23%E2%9C%93/results"
                assert resource["mimeType"] == "application/json"
                rows = json.loads(resource["text"])
                assert rows["records"] == [{"day": "Monday", "sessions": 12}]
                assert rows["stale"] and rows["evidence"]["durationMs"] == 17
                assert rows["evidence"]["parameters"][0]["value"] == "US"
                assert rows["returnedRecords"] == 1 and rows["truncated"] and rows["cached"]
                assert json.loads(reply["result"]["content"][0]["text"]) == rows
                for identifier, action in [(21, "get_document"), (22, "set_ready")]:
                    reply = call(identifier, "content_metadata", {"action": action, "id": memory_id, "version": 1})
                    assert not failed(reply), reply
                    link = next(item for item in reply["result"]["content"] if item["type"] == "resource_link")
                    assert link["uri"] == f"bosca://content/metadata/{memory_id}"
                reply = call(23, "content_metadata", {"action": "delete", "id": memory_id})
                assert not failed(reply), reply
                assert all(item["type"] != "resource_link" for item in reply["result"]["content"])
                expected_operations += ["ExecuteAnalyticsQueryByKey", "GetMetadataDocument", "SetMetadataReady", "DeleteMetadata"]
                assert operations == expected_operations, "Rich resources must not require additional API reads"
                for identifier, action in enumerate(["list", "books", "references", "verses"], 30):
                    reply = call(identifier, "content_scripture", {"action": action, "id": memory_id,
                        "version": 2, "variant": "reader", "reference": "John 3:16"})
                    assert not failed(reply), reply
                    data = json.loads(reply["result"]["content"][0]["text"])["content"]
                    if action == "list":
                        assert data["findMetadata"][0]["bibles"] == [bible]
                    else:
                        edition = data["metadata"]["bible"]
                        assert edition["variant"] == "reader" and edition["version"] == 2
                        assert variables[-1]["version"] == 2 and variables[-1]["variant"] == "reader"
                        if action == "verses":
                            assert edition["aiContext"] == [passage]
                expected_operations += ["ListScriptureBibles", "GetScriptureBooks", "ResolveScriptureReferences", "GetScriptureVerses"]
                state["scripture_denied"] = True
                denied = call(34, "content_scripture", {"action": "verses", "id": memory_id,
                    "version": 2, "variant": "reader", "reference": "John 3:16"})
                assert failed(denied), denied
                assert "Exact fixture text" not in json.dumps(denied)
                expected_operations += ["GetScriptureVerses"]
                assert operations == expected_operations, "Scripture denial must not fall back to display reads"
                document = {"document": {"type": "doc", "content": [
                    {"type": "heading", "attrs": {"level": 1}, "content": [{"type": "text", "text": "Article"}]},
                    {"type": "paragraph", "content": [{"type": "text", "text": "Exact fixture text", "marks": [{"type": "italic"}]},
                        {"type": "text", "text": "1", "marks": [{"type": "superscript"}]}]}]}}
                created = call(35, "content_metadata", {"action": "create", "name": "Article", "documentContent": document,
                    "parentCollectionId": conversation_id, "templateId": conversation_id, "templateVersion": 2})
                assert not failed(created), created
                assert created["result"]["content"][1]["uri"] == f"bosca://content/metadata/{memory_id}"
                assert variables[-1]["metadata"]["contentType"] == "bosca/v-document"
                assert variables[-1]["metadata"]["document"]["title"] == "Article"
                assert variables[-1]["metadata"]["document"]["content"] == document
                read = call(36, "content_metadata", {"action": "get_document", "id": memory_id, "version": 1})
                assert not failed(read), read
                assert json.loads(read["result"]["content"][0]["text"])["content"] == document
                edited = call(37, "content_metadata", {"action": "set_content", "id": memory_id,
                    "documentTitle": "Revised article", "documentContent": document})
                assert not failed(edited), edited
                assert variables[-1]["version"] == 1 and variables[-1]["collaborationSync"] == "RESET"
                read = call(38, "content_metadata", {"action": "get_document", "id": memory_id, "version": 1})
                assert not failed(read), read
                result = json.loads(read["result"]["content"][0]["text"])
                assert result["title"] == "Revised article" and result["content"] == document
                assert result["templateId"] == conversation_id and result["templateVersion"] == 2
                expected_operations += ["AddMetadata", "GetMetadataDocument", "GetMetadata", "SetDocument", "GetMetadataDocument"]
                assert operations == expected_operations, "Document creation must write once without readiness or publication"
                for identifier, action in enumerate(["list", "get", "validate", "create", "edit", "enable", "disable", "execute", "delete"], 40):
                    arguments = {"action": action, "source": "main { null }", "compile": True,
                                 "script": {"key": "fixture", "name": "Fixture", "source": "main { null }"},
                                 "expectedVersion": 3, "input": [1, 2]}
                    if action in {"get", "edit", "enable", "disable", "execute", "delete"}:
                        arguments["id"] = memory_id
                    reply = call(identifier, "bosca_script", arguments)
                    assert not failed(reply), reply
                    if action == "execute":
                        assert json.loads(reply["result"]["content"][0]["text"])["scripts"]["executeScript"] == [1, "result", None]
                        assert variables[-1]["input"] == [1, 2]
                    if action == "edit":
                        assert variables[-1]["expectedVersion"] == 3
                        assert variables[-1]["script"]["configuration"] == {"keep": True}
                expected_operations += ["ListManagedScripts", "GetManagedScript", "ValidateManagedScript", "AddManagedScript",
                    "GetManagedScript", "EditManagedScript", "GetManagedScript", "EnableManagedScript",
                    "GetManagedScript", "DisableManagedScript", "GetManagedScript", "ExecuteManagedScript",
                    "GetManagedScript", "DeleteManagedScript"]
                reply = call(49, "bosca_script", {"action": "create", "dryRun": True, "compile": True,
                    "script": {"key": "preview", "name": "Preview", "source": "main { null }"}})
                assert not failed(reply), reply
                assert json.loads(reply["result"]["content"][0]["text"])["dryRun"]
                expected_operations += ["ValidateManagedScript"]
                assert operations == expected_operations
                before = len(operations)
                reply = call(60, "bosca_pipeline", {"action": "run", "id": memory_id, "payload": {}})
                assert reply["result"]["isError"] and len(operations) == before
                for identifier, action in enumerate(["list", "get", "catalog", "validate", "save", "dry_run", "run", "run_status"], 61):
                    arguments = {"action": action}
                    if action in {"get", "save", "dry_run", "run"}:
                        arguments["id"] = memory_id
                    if action == "save":
                        arguments.update(expectedVersion=3, pipeline={"name": "Updated"})
                    if action == "validate":
                        arguments["graph"] = pipeline_graph
                    if action in {"dry_run", "run"}:
                        arguments.update(payload=[1, 2], confirmed=True)
                    if action == "run_status":
                        arguments["runId"] = conversation_id
                    reply = call(identifier, "bosca_pipeline", arguments)
                    assert not reply["result"].get("isError"), reply
                    if action == "run":
                        result = json.loads(reply["result"]["content"][0]["text"])["pipelines"]["run"]
                        assert result["status"] == "SUSPENDED" and result["runId"] == conversation_id
                        assert reply["result"]["content"][1]["uri"] == f"bosca://pipelines/runs/{conversation_id}"
                    if action == "run_status":
                        assert json.loads(reply["result"]["content"][0]["text"])["pipelines"]["run"]["output"] == {"result": 7}
                before = len(operations)
                reply = call(69, "bosca_pipeline", {"action": "save", "preview": True,
                    "pipeline": {"name": "New", "acceptedInputType": "JSON", "graph": pipeline_graph}})
                assert not reply["result"].get("isError"), reply
                assert operations[before:] == ["ValidateManagedPipeline"]
                assert operations.count("RunManagedPipeline") == 1
                expected_operations += ["ListManagedPipelines", "GetManagedPipeline", "GetPipelineCatalog", "ValidateManagedPipeline",
                    "GetManagedPipeline", "ValidateManagedPipeline", "SaveManagedPipeline", "DryRunManagedPipeline",
                    "RunManagedPipeline", "GetManagedPipelineRun", "ValidateManagedPipeline"]
                assert operations == expected_operations

                for identifier, arguments in enumerate([
                    {"action": "catalogs", "offset": 10, "limit": 5},
                    {"action": "schemas", "catalog": "warehouse"},
                    {"action": "tables", "catalog": "warehouse", "schema": "bosca"},
                    {"action": "describe", "catalog": "warehouse", "schema": "bosca", "table": "events"},
                    {"action": "execute", "sql": "SELECT count(*) AS sessions FROM warehouse.bosca.events",
                     "limit": 1, "visualizationType": "NUMBER", "configuration": {"value": "sessions"}},
                ], 70):
                    reply = call(identifier, "analytics_sql", arguments)
                    assert not failed(reply), reply
                    if arguments["action"] == "execute":
                        evidence = json.loads(reply["result"]["content"][0]["text"])
                        assert evidence["sql"] == arguments["sql"]
                        assert evidence["records"] == [{"sessions": 42}]
                        assert evidence["truncated"] and evidence["durationMs"] == 7
                        assert evidence["visualizationType"] == "NUMBER"
                        resource = reply["result"]["content"][1]["resource"]
                        assert resource["uri"].startswith("bosca://analytics/sql/")
                        assert json.loads(resource["text"]) == evidence
                expected_operations += ["ListAnalyticsCatalogs", "ListAnalyticsSchemas", "ListAnalyticsTables",
                    "DescribeAnalyticsTable", "ExecuteAnalyticsSql"]
                assert operations == expected_operations

                for identifier, arguments in enumerate([
                    {"action": "generate", "prompt": "a bird", "parentCollectionId": conversation_id},
                    {"action": "edit", "sourceId": memory_id, "prompt": "blue", "name": "Blue bird"},
                    {"action": "get", "id": memory_id},
                ], 80):
                    reply = call(identifier, "content_image", arguments)
                    assert not failed(reply), reply
                    blocks = reply["result"]["content"]
                    assert json.loads(blocks[0]["text"])["id"] == memory_id
                    assert blocks[1]["uri"] == f"bosca://content/metadata/{memory_id}"
                    assert blocks[2]["type"] == "image" and blocks[2]["mimeType"] == "image/png"
                    assert base64.b64decode(blocks[2]["data"]) == image_bytes
                assert image_downloads == [(None, "fixture")] * 3
                state["image_preview_denied"] = True
                reply = call(83, "content_image", {"action": "generate", "prompt": "a bird"})
                assert not failed(reply), reply
                assert reply["result"]["content"][1]["uri"] == f"bosca://content/metadata/{memory_id}"
                assert "Use get" in reply["result"]["content"][-1]["text"]
                assert operations.count("GenerateContentImage") == 2
                expected_operations += ["GenerateContentImage", "GetMetadataDownload", "EditContentImage", "GetMetadataDownload",
                    "GetContentImage", "GetMetadataDownload", "GenerateContentImage", "GetMetadataDownload"]
                assert operations == expected_operations

                crop = {"top": 1, "left": 2, "width": 300, "height": 200}
                reply = call(84, "content_image", {"action": "attach", "id": memory_id,
                    "targetMetadataId": conversation_id, "attributeKey": "hero", "crop": crop})
                assert not failed(reply), reply
                blocks = reply["result"]["content"]
                attachment = json.loads(blocks[0]["text"])["content"]["images"]["attach"]
                assert attachment["attached"] and not attachment["optimized"]
                assert attachment["crop"] == crop
                assert attachment["attribute"]["targetSize"] == [740, 416]
                assert "optimization unavailable" in attachment["warning"]
                assert blocks[1]["uri"] == f"bosca://content/metadata/{conversation_id}"
                assert len(blocks) == 2 and image_downloads == [(None, "fixture")] * 3
                assert variables[-1]["imageMetadataId"] == memory_id
                assert variables[-1]["targetMetadataId"] == conversation_id
                assert variables[-1]["crop"] == crop
                expected_operations += ["AttachContentImage"]
                assert operations == expected_operations

                state["revoked"] = True
                assert failed(call(8, "analytics_query", {"action": "list"}))
                assert failed(call(9, "analytics_query", {"action": "list"}))
                assert len(credentials) == len(expected_operations) + 2
                assert credentials == ["Bearer bsk_native_runtime"] * len(credentials)
                assert operations == expected_operations + ["ListAnalyticsQueries"] * 2, "API tokens must never exchange, refresh, or fall back to login"
                assert not (directory / "config" / "bosca" / "config.json").exists(), "Explicit API token was persisted to a CLI profile"
            finally:
                process.terminate()
                try:
                    process.communicate(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.communicate(timeout=5)
                diagnostics.close()
    finally:
        if args.launcher_environment:
            args.launcher_environment.unlink(missing_ok=True)
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
    print("Native harness MCP smoke passed: discovery, typed analytics/content resources, warehouse discovery/SQL provenance/chart evidence, image generation/editing, signed previews and template attachment with crop and post-processing warnings, memory operations/read tracking, attributed Scripture and use denial, scripting workflow and dry run, pipeline catalog/save/preview/confirmed durable run/poll, direct API token, rejection without refresh, and no saved credentials")


if __name__ == "__main__":
    main()
