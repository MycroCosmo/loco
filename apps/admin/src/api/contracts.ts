export function object(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('서버 응답 형식이 올바르지 않습니다.')
  return Object.fromEntries(Object.entries(value))
}
export function text(value: unknown): string {
  if (typeof value !== 'string') throw new Error('문자열 응답이 필요합니다.')
  return value
}
export function number(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error('숫자 응답이 필요합니다.')
  return value
}
export function boolean(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new Error('참/거짓 응답이 필요합니다.')
  return value
}
export function nullableText(value: unknown): string | null { return value === null ? null : text(value) }
export function list<T>(parse: (value: unknown) => T) {
  return (value: unknown): T[] => {
    if (!Array.isArray(value)) throw new Error('목록 응답이 필요합니다.')
    return value.map(parse)
  }
}
export function identity(value: unknown) {
  const v = object(value)
  return { id: text(v.id), loginId: text(v.loginId), displayName: text(v.displayName),
    active: boolean(v.active), authVersion: number(v.authVersion),
    mustChangePassword: boolean(v.mustChangePassword), systemAdmin: boolean(v.systemAdmin) }
}
export type Identity = ReturnType<typeof identity>
export function explanation(value: unknown) {
  const v = object(value)
  return { accountId: text(v.accountId), permissionKey: text(v.permissionKey), action: text(v.action),
    allowed: boolean(v.allowed), reason: text(v.reason), grants: list(value => {
      const g = object(value)
      return { roleId: text(g.roleId), roleCode: text(g.roleCode), grantedAction: text(g.grantedAction), impliesRead: boolean(g.impliesRead) }
    })(v.grants) }
}
export function menuNode(value: unknown) {
  const v = object(value)
  const type = text(v.type)
  if (type !== 'GROUP' && type !== 'PAGE') throw new Error('알 수 없는 메뉴 유형입니다.')
  return { id: text(v.id), parentId: nullableText(v.parentId), sortOrder: number(v.sortOrder),
    title: text(v.title), type, routeKey: nullableText(v.routeKey), permissionKey: nullableText(v.permissionKey), visible: boolean(v.visible) }
}
export function tree(value: unknown) {
  const v = object(value)
  return { revision: number(v.revision), nodes: list(menuNode)(v.nodes) }
}
export type Tree = ReturnType<typeof tree>
export type MenuNode = ReturnType<typeof menuNode>

export function action(value: unknown): 'READ' | 'MANAGE' {
  if (value !== 'READ' && value !== 'MANAGE') throw new Error('알 수 없는 권한 동작입니다.')
  return value
}
export function role(value: unknown) {
  const v = object(value)
  const permissions: Record<string, 'READ' | 'MANAGE'> = {}
  for (const [key, value] of Object.entries(object(v.permissions))) permissions[key] = action(value)
  return { id: text(v.id), code: text(v.code), name: text(v.name), version: number(v.version), systemProtected: boolean(v.systemProtected), permissions }
}
export type Role = ReturnType<typeof role>
export function assignment(value: unknown) {
  const v = object(value)
  return { accountId: text(v.accountId), roleIds: list(text)(v.roleIds), authVersion: number(v.authVersion) }
}
export function permission(value: unknown) {
  const v = object(value)
  return { key: text(v.key), label: text(v.label), allowedActions: list(action)(v.allowedActions), systemOnly: boolean(v.systemOnly) }
}
export function roleTemplate(value: unknown) {
  const v = object(value)
  return { code: text(v.code), action: action(v.action) }
}
export function movePreview(value: unknown) {
  const v = object(value)
  return { id: text(v.id), oldParentId: nullableText(v.oldParentId), newParentId: nullableText(v.newParentId),
    beforeSiblingId: nullableText(v.beforeSiblingId), revision: number(v.revision), permissionsChanged: boolean(v.permissionsChanged), message: text(v.message) }
}
export function storedFile(value: unknown) {
  const v = object(value)
  return { id:text(v.id), originalName:text(v.originalName), mediaType:text(v.mediaType), byteSize:number(v.byteSize),
    sha256:text(v.sha256), createdBy:text(v.createdBy), createdAt:text(v.createdAt) }
}
export function historyEntry(value: unknown) {
  const v = object(value)
  return { id:text(v.id), actorId:text(v.actorId), action:text(v.action), resourceType:text(v.resourceType), resourceId:text(v.resourceId),
    occurredAt:text(v.occurredAt), before:v.before===null?null:object(v.before), after:v.after===null?null:object(v.after), payloadVersion:number(v.payloadVersion) }
}
export function historyPage(value: unknown) {
  const v = object(value)
  return {items:list(historyEntry)(v.items), nextCursor:nullableText(v.nextCursor)}
}
