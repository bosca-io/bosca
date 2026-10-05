/**
 * Opens a page's create dialog when the route carries the `new` query param
 * (e.g. `/cms/documents?new=1` — used by the command palette's quick
 * actions). The param is stripped via a history replace so refresh and
 * back/forward don't re-trigger the dialog.
 */
export function useCreateFromQuery(open: () => void) {
  const route = useRoute()
  const router = useRouter()

  onMounted(() => {
    if (route.query.new === undefined) return
    const { new: _consumed, ...rest } = route.query
    void router.replace({ query: rest })
    open()
  })
}
