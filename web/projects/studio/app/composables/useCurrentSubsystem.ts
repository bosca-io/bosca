export function useCurrentSubsystem() {
  const route = useRoute()
  const { getSubsystem } = useSubsystems()

  const id = computed(() => {
    const segments = route.path.split('/').filter(Boolean)
    return segments[0] || 'analytics'
  })

  const subsystem = computed(() => getSubsystem(id.value))
  const accent = computed(() => subsystem.value?.accent ?? '#5ec5ff')

  return { id, subsystem, accent }
}
