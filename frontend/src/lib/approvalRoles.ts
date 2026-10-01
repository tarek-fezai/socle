// SPDX-License-Identifier: AGPL-3.0-or-later
type Api = {
  get: <T>(url: string) => Promise<{ data: T }>
  post: <T>(url: string, body: unknown) => Promise<{ data: T }>
  put: <T>(url: string, body: unknown) => Promise<{ data: T }>
  delete: (url: string) => Promise<unknown>
}

export type ApprovalRoleAssignment = {
  id: string
  roleId: string
  roleName: string
  subjectType: 'user' | 'group'
  subjectId: string
  scopeType: 'all' | 'space' | 'tag' | 'doc_type'
  scopeRef: string | null
  grantedBy: string | null
  grantedAt: string | null
}

export type ApprovalRoleAssignmentCreate = {
  roleId: string
  subjectType: 'user' | 'group'
  subjectId: string
  scopeType: 'all' | 'space' | 'tag' | 'doc_type'
  scopeRef?: string | null
}

export async function listApprovalRoleAssignments(
  api: Api,
  params?: { roleId?: string; spaceId?: string },
) {
  const q = new URLSearchParams()
  if (params?.roleId) q.set('roleId', params.roleId)
  if (params?.spaceId) q.set('spaceId', params.spaceId)
  const qs = q.toString()
  const { data } = await api.get<ApprovalRoleAssignment[]>(
    `/api/v1/approval-role-assignments${qs ? `?${qs}` : ''}`,
  )
  return data
}

export async function createApprovalRoleAssignment(api: Api, body: ApprovalRoleAssignmentCreate) {
  const { data } = await api.post<ApprovalRoleAssignment>('/api/v1/approval-role-assignments', body)
  return data
}

export async function deleteApprovalRoleAssignment(api: Api, id: string) {
  await api.delete(`/api/v1/approval-role-assignments/${id}`)
}

export function scopeLabel(scopeType: ApprovalRoleAssignment['scopeType'], scopeRef: string | null): string {
  switch (scopeType) {
    case 'all':
      return 'Tous les espaces'
    case 'space':
      return `Espace ${scopeRef ?? '—'}`
    case 'tag':
      return `Tag ${scopeRef ?? '—'}`
    case 'doc_type':
      return `Type ${scopeRef ?? '—'}`
    default:
      return scopeType
  }
}
