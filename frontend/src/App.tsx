// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './auth/AuthProvider'
import { RequireAuth } from './auth/RequireAuth'
import { AppShellLayout } from './components/shell/AppShell'
import { DocumentsPage } from './pages/DocumentsPage'
import { DocumentEditPage } from './pages/DocumentEditPage'
import { DocumentHistoryPage } from './pages/DocumentHistoryPage'
import { DocumentComparePage } from './pages/DocumentComparePage'
import { NewDocumentPage } from './pages/NewDocumentPage'
import { TemplatesAdminPage } from './pages/TemplatesAdminPage'
import { TagsAdminPage } from './pages/TagsAdminPage'
import { CustomFieldsAdminPage } from './pages/CustomFieldsAdminPage'
import { RetentionAdminPage } from './pages/RetentionAdminPage'
import { BrandingAdminPage } from './pages/BrandingAdminPage'
import { LicenceAdminPage } from './pages/LicenceAdminPage'
import { AccessPage } from './pages/AccessPage'
import { ApprovalsPage } from './pages/ApprovalsPage'
import { ApprovalDiffPage } from './pages/approvals/ApprovalDiffPage'
import { NotificationsPage } from './pages/NotificationsPage'
import { AuditPage } from './pages/AuditPage'
import { TrashPage } from './pages/TrashPage'
import { WebhookDeliveriesPage } from './pages/WebhookDeliveriesPage'
import { IntegrationsPage } from './pages/IntegrationsPage'
import { WorkflowsPage } from './pages/WorkflowsPage'
import { ApprovalRolesPage } from './pages/ApprovalRolesPage'
import { SpacesPage } from './pages/SpacesPage'
import { SpaceSettingsPage } from './pages/SpaceSettingsPage'
import { SpaceBrowsePage } from './pages/SpaceBrowsePage'
import { FolderPage } from './pages/FolderPage'
import { TeamPage } from './pages/TeamPage'
import { SearchPage } from './pages/SearchPage'
import { DocumentReadPage } from './pages/document/DocumentReadPage'
import { DocumentViewRedirect } from './pages/document/DocumentViewRedirect'
import { GraphPage } from './pages/GraphPage'
import { ContentHealthPage } from './pages/ContentHealthPage'
import { DocumentExportPage, FolderExportPage, TagExportPage } from './pages/ExportPage'
import { CallbackPage } from './pages/CallbackPage'
import { SilentRenewPage } from './pages/SilentRenewPage'
import { LoginPage, LoginErrorPage } from './pages/login/LoginPage'
import { HomeDashboardPage } from './pages/home/HomeDashboardPage'
import { FavoritesPage } from './pages/FavoritesPage'
import { AccountPage } from './pages/account/AccountPage'
import { AdminHomePage } from './pages/admin/AdminHomePage'
import { RequirePlatformAdmin } from './auth/RequirePlatformAdmin'

function ProtectedShell() {
  return (
    <RequireAuth>
      <AppShellLayout />
    </RequireAuth>
  )
}

export default function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/login/erreur" element={<LoginErrorPage />} />
        <Route path="/callback" element={<CallbackPage />} />
        <Route path="/silent-renew" element={<SilentRenewPage />} />

        <Route element={<ProtectedShell />}>
          <Route path="/" element={<HomeDashboardPage />} />
          <Route path="/favorites" element={<FavoritesPage />} />
          <Route path="/docs" element={<DocumentsPage />} />
          <Route path="/docs/new" element={<NewDocumentPage />} />
          <Route path="/account" element={<AccountPage />} />
          <Route path="/account/tokens/new" element={<AccountPage generateToken />} />
          <Route path="/admin" element={<RequirePlatformAdmin />}>
            <Route index element={<AdminHomePage />} />
            <Route path="templates" element={<TemplatesAdminPage />} />
            <Route path="tags" element={<TagsAdminPage />} />
            <Route path="custom-fields" element={<CustomFieldsAdminPage />} />
            <Route path="retention" element={<RetentionAdminPage />} />
            <Route path="branding" element={<BrandingAdminPage />} />
            <Route path="licence" element={<LicenceAdminPage />} />
            <Route path="workflows" element={<WorkflowsPage />} />
            <Route path="approval-roles" element={<ApprovalRolesPage />} />
          </Route>
          <Route path="/docs/:id" element={<DocumentReadPage />} />
          <Route path="/docs/:id/edit" element={<DocumentEditPage />} />
          <Route path="/docs/:id/view" element={<DocumentViewRedirect />} />
          <Route path="/docs/:id/history" element={<DocumentHistoryPage />} />
          <Route path="/docs/:id/history/compare" element={<DocumentComparePage />} />
          <Route path="/docs/:id/export" element={<DocumentExportPage />} />
          <Route path="/folders/:id/export" element={<FolderExportPage />} />
          <Route path="/tags/:id/export" element={<TagExportPage />} />
          <Route path="/documents/:id/history" element={<DocumentHistoryPage />} />
          <Route path="/approvals" element={<ApprovalsPage />} />
          <Route path="/approvals/:requestId" element={<ApprovalsPage />} />
          <Route path="/approvals/:requestId/diff" element={<ApprovalDiffPage />} />
          <Route path="/notifications" element={<NotificationsPage />} />
          <Route path="/audit" element={<AuditPage />} />
          <Route path="/trash" element={<TrashPage />} />
          <Route path="/integrations" element={<IntegrationsPage />} />
          <Route path="/integrations/webhooks/deliveries" element={<WebhookDeliveriesPage />} />
          <Route path="/spaces" element={<SpacesPage />} />
          <Route path="/spaces/:spaceId" element={<SpaceSettingsPage />} />
          <Route path="/spaces/:spaceId/tree" element={<SpaceBrowsePage />} />
          <Route path="/folders/:id" element={<FolderPage />} />
          <Route path="/spaces/:spaceId/graph" element={<GraphPage />} />
          <Route path="/spaces/:spaceId/content-health" element={<ContentHealthPage />} />
          <Route path="/team" element={<TeamPage />} />
          <Route path="/search" element={<SearchPage />} />
          <Route path="/access" element={<Navigate to="/spaces" replace />} />
          <Route path="/spaces/:spaceId/access" element={<AccessPage objectType="space" />} />
          <Route path="/folders/:folderId/access" element={<AccessPage objectType="folder" />} />
          <Route path="/documents/:documentId/access" element={<AccessPage objectType="document" />} />
        </Route>
      </Routes>
    </AuthProvider>
  )
}
