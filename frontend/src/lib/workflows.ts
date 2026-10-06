// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
export type WorkflowStep = {
  id?: string
  stepOrder: number
  slaHours: number | null
  approverRoleId: string | null
  approverRoleName?: string | null
  escalatesToStepOrder: number | null
}

export type WorkflowDefinition = {
  id: string
  name: string
  scopeSpaceId: string | null
  scopeDocType: string | null
  status: 'active' | 'draft'
  createdAt: string | null
  steps: WorkflowStep[]
  inProgressCount: number
}

export type ApplicableWorkflow = {
  id: string
  name: string
  stepCount: number
  matchLevel: 'space_type' | 'space' | 'type' | 'global' | 'fallback'
  steps: WorkflowStep[]
}

export type GlobalRole = {
  id: string
  name: string
  description: string | null
}

export type WorkflowUpsert = {
  name: string
  scopeSpaceId: string | null
  scopeDocType: string | null
  status: 'active' | 'draft'
  steps: Array<{
    stepOrder: number
    slaHours: number | null
    approverRoleId: string | null
    escalatesToStepOrder: number | null
  }>
}

export type Api = {
  get: <T>(url: string) => Promise<{ data: T }>
  post: <T>(url: string, body: unknown) => Promise<{ data: T }>
  put: <T>(url: string, body: unknown) => Promise<{ data: T }>
  delete: (url: string) => Promise<unknown>
}

export async function listWorkflowDefinitions(api: Api) {
  const { data } = await api.get<WorkflowDefinition[]>('/api/v1/approval-workflows')
  return data
}

export async function getWorkflowDefinition(api: Api, id: string) {
  const { data } = await api.get<WorkflowDefinition>(`/api/v1/approval-workflows/${id}`)
  return data
}

export async function createWorkflowDefinition(api: Api, body: WorkflowUpsert) {
  const { data } = await api.post<WorkflowDefinition>('/api/v1/approval-workflows', body)
  return data
}

export async function updateWorkflowDefinition(api: Api, id: string, body: WorkflowUpsert) {
  const { data } = await api.put<WorkflowDefinition>(`/api/v1/approval-workflows/${id}`, body)
  return data
}

export async function deleteWorkflowDefinition(api: Api, id: string) {
  await api.delete(`/api/v1/approval-workflows/${id}`)
}

export async function listGlobalRoles(api: Api) {
  const { data } = await api.get<GlobalRole[]>('/api/v1/global-roles')
  return data
}

export async function fetchApplicableWorkflow(api: Api, documentId: string) {
  const { data } = await api.get<ApplicableWorkflow>(
    `/api/v1/documents/${documentId}/approvals/applicable-workflow`,
  )
  return data
}

export function matchLevelLabel(level: ApplicableWorkflow['matchLevel']): string {
  switch (level) {
    case 'space_type':
      return 'espace + type'
    case 'space':
      return 'espace'
    case 'type':
      return 'type de document'
    case 'global':
      return 'définition globale'
    case 'fallback':
      return 'défaut (Approbation simple)'
  }
}
