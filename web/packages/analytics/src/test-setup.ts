import { afterEach, beforeEach, vi } from 'vitest'

// Browser test files share an origin. Give each test its own real IndexedDB
// database so an earlier SDK instance cannot block deletion or leak events.
if (typeof indexedDB !== 'undefined') {
  const openDatabase = indexedDB.open.bind(indexedDB)
  const deleteDatabase = indexedDB.deleteDatabase.bind(indexedDB)
  let requests: IDBOpenDBRequest[] = []
  let names = new Set<string>()

  beforeEach(() => {
    const prefix = `bosca-test-${crypto.randomUUID()}-`
    requests = []
    names = new Set()
    vi.spyOn(indexedDB, 'open').mockImplementation((name, version) => {
      const databaseName = prefix + name
      names.add(databaseName)
      const request = version === undefined ? openDatabase(databaseName) : openDatabase(databaseName, version)
      requests.push(request)
      return request
    })
    vi.spyOn(indexedDB, 'deleteDatabase').mockImplementation(name => deleteDatabase(prefix + name))
  })

  afterEach(async () => {
    await Promise.all(requests.map(request => new Promise<void>(resolve => {
      const close = () => {
        if (request.readyState === 'done' && !request.error) request.result.close()
        resolve()
      }
      if (request.readyState === 'done') close()
      else {
        request.addEventListener('success', close, { once: true })
        request.addEventListener('error', () => resolve(), { once: true })
      }
    })))
    for (const name of names) deleteDatabase(name)
    vi.restoreAllMocks()
  })
}
