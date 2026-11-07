import type { permission } from '../api/contracts'

export function templatePermissions(permissions: ReturnType<typeof permission>[], action: ReturnType<typeof permission>['allowedActions'][number] | undefined) {
  return permissions.filter(p => !p.systemOnly && action !== undefined && p.allowedActions.includes(action))
}
