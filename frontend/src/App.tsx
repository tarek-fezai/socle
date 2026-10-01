// SPDX-License-Identifier: AGPL-3.0-or-later
import { Navigate, Route, Routes, Link } from 'react-router-dom'
import { AuthProvider, useAuth } from './auth/AuthProvider'
import { RequireAuth } from './auth/RequireAuth'
import { AppNav } from './components/AppNav'
import { SocleRole } from './lib/auth'
import { DocumentsPage } from './pages/DocumentsPage'
import { DocumentEditPage } from './pages/DocumentEditPage'
import { DocumentHistoryPage } from './pages/DocumentHistoryPage'
import { NewDocumentPage } from './pages/NewDocumentPage'
import { TemplatesAdminPage } from './pages/TemplatesAdminPage'
import { AccessPage } from './pages/AccessPage'
import { ApprovalsPage } from './pages/ApprovalsPage'
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
import { CompositePage } from './pages/CompositePage'
import { GraphPage } from './pages/GraphPage'
import { ContentHealthPage } from './pages/ContentHealthPage'
import { DocumentExportPage, FolderExportPage, TagExportPage } from './pages/ExportPage'
import { CallbackPage } from './pages/CallbackPage'
import { SilentRenewPage } from './pages/SilentRenewPage'

function HomePage() {
  const { me, login, logout, loading, authenticated, organizationName } = useAuth()
  const roles = me?.roles ?? []
  const isAuditeur = roles.includes(SocleRole.AUDITEUR)
  const isIntegrateur = roles.includes(SocleRole.INTEGRATEUR)
  const isAdmin = roles.includes(SocleRole.ADMINISTRATEUR_SYSTEME)

  return (
    <main className="mx-auto flex min-h-[calc(100vh-60px)] max-w-3xl flex-col justify-center px-6 py-16">
      <p className="mb-3 text-sm font-medium tracking-[0.2em] text-socle-accent uppercase">
        {organizationName} · Documentation
      </p>
      <h1 className="font-display text-5xl font-normal text-socle-ink md:text-6xl">Socle</h1>
      <p className="mt-4 max-w-xl text-lg text-socle-slate">
        Documentation d&apos;entreprise — auth OIDC, API Spring protégée.
      </p>

      <div className="mt-10 flex flex-wrap items-center gap-4">
        {!loading && !authenticated && (
          <button type="button" onClick={() => void login()} className="btn-primary">
            Se connecter
          </button>
        )}
        {authenticated && (
          <>
            <span className="text-sm text-socle-slate">
              {me ? `${me.displayName} (${me.email})` : 'Connecté'}
            </span>
            <Link className="font-semibold text-socle-accent underline-offset-4 hover:underline" to="/docs">
              Documents →
            </Link>
            <Link
              className="font-semibold text-socle-accent underline-offset-4 hover:underline"
              to="/docs/new"
            >
              Nouveau document →
            </Link>
            {isAdmin && (
              <Link
                className="font-semibold text-socle-accent underline-offset-4 hover:underline"
                to="/admin/templates"
              >
                Modèles →
              </Link>
            )}
            <Link className="font-semibold text-socle-accent underline-offset-4 hover:underline" to="/approvals">
              Approbations →
            </Link>
            <Link
              className="font-semibold text-socle-accent underline-offset-4 hover:underline"
              to="/notifications"
            >
              Notifications →
            </Link>
            {isAuditeur && (
              <Link className="font-semibold text-socle-accent underline-offset-4 hover:underline" to="/audit">
                Audit →
              </Link>
            )}
            <Link className="font-semibold text-socle-accent underline-offset-4 hover:underline" to="/trash">
              Corbeille →
            </Link>
            <Link
              className="font-semibold text-socle-accent underline-offset-4 hover:underline"
              to="/admin/workflows"
            >
              Workflows →
            </Link>
            {isIntegrateur && (
              <Link
                className="font-semibold text-socle-accent underline-offset-4 hover:underline"
                to="/integrations"
              >
                Intégrations →
              </Link>
            )}
            {(isAuditeur || isIntegrateur) && (
              <Link
                className="font-semibold text-socle-accent underline-offset-4 hover:underline"
                to="/integrations/webhooks/deliveries"
              >
                Livraisons webhook →
              </Link>
            )}
            <Link
              className="font-semibold text-socle-accent underline-offset-4 hover:underline"
              to="/search"
            >
              Recherche →
            </Link>
            <Link
              className="font-semibold text-socle-accent underline-offset-4 hover:underline"
              to="/spaces"
            >
              Espaces →
            </Link>
            <Link
              className="font-semibold text-socle-accent underline-offset-4 hover:underline"
              to="/team"
            >
              Équipes →
            </Link>
            <button
              type="button"
              onClick={() => void logout()}
              className="text-sm text-socle-slate underline-offset-4 hover:underline"
            >
              Déconnexion
            </button>
          </>
        )}
      </div>
      <p className="mt-6 text-xs text-socle-muted">
        Rôles plateforme (via /me) — CONTRIBUTEUR · AUDITEUR · INTEGRATEUR · ADMINISTRATEUR_SYSTEME
      </p>
    </main>
  )
}

function AppShell() {
  const { authenticated, loading } = useAuth()
  return (
    <>
      {!loading && authenticated ? <AppNav /> : null}
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/callback" element={<CallbackPage />} />
        <Route path="/silent-renew" element={<SilentRenewPage />} />
        <Route
          path="/docs"
          element={
            <RequireAuth>
              <DocumentsPage />
            </RequireAuth>
          }
        />
        <Route
          path="/docs/new"
          element={
            <RequireAuth>
              <NewDocumentPage />
            </RequireAuth>
          }
        />
        <Route
          path="/admin/templates"
          element={
            <RequireAuth>
              <TemplatesAdminPage />
            </RequireAuth>
          }
        />
        <Route
          path="/docs/:id"
          element={
            <RequireAuth>
              <DocumentEditPage />
            </RequireAuth>
          }
        />
        <Route
          path="/docs/:id/view"
          element={
            <RequireAuth>
              <CompositePage />
            </RequireAuth>
          }
        />
        <Route
          path="/docs/:id/history"
          element={
            <RequireAuth>
              <DocumentHistoryPage />
            </RequireAuth>
          }
        />
        <Route
          path="/docs/:id/export"
          element={
            <RequireAuth>
              <DocumentExportPage />
            </RequireAuth>
          }
        />
        <Route
          path="/folders/:id/export"
          element={
            <RequireAuth>
              <FolderExportPage />
            </RequireAuth>
          }
        />
        <Route
          path="/tags/:id/export"
          element={
            <RequireAuth>
              <TagExportPage />
            </RequireAuth>
          }
        />
        <Route
          path="/documents/:id/history"
          element={
            <RequireAuth>
              <DocumentHistoryPage />
            </RequireAuth>
          }
        />
        <Route
          path="/approvals"
          element={
            <RequireAuth>
              <ApprovalsPage />
            </RequireAuth>
          }
        />
        <Route
          path="/notifications"
          element={
            <RequireAuth>
              <NotificationsPage />
            </RequireAuth>
          }
        />
        <Route
          path="/audit"
          element={
            <RequireAuth>
              <AuditPage />
            </RequireAuth>
          }
        />
        <Route
          path="/trash"
          element={
            <RequireAuth>
              <TrashPage />
            </RequireAuth>
          }
        />
        <Route
          path="/integrations"
          element={
            <RequireAuth>
              <IntegrationsPage />
            </RequireAuth>
          }
        />
        <Route
          path="/admin/workflows"
          element={
            <RequireAuth>
              <WorkflowsPage />
            </RequireAuth>
          }
        />
        <Route
          path="/admin/approval-roles"
          element={
            <RequireAuth>
              <ApprovalRolesPage />
            </RequireAuth>
          }
        />
        <Route
          path="/integrations/webhooks/deliveries"
          element={
            <RequireAuth>
              <WebhookDeliveriesPage />
            </RequireAuth>
          }
        />
        <Route
          path="/spaces"
          element={
            <RequireAuth>
              <SpacesPage />
            </RequireAuth>
          }
        />
        <Route
          path="/spaces/:spaceId"
          element={
            <RequireAuth>
              <SpaceSettingsPage />
            </RequireAuth>
          }
        />
        <Route
          path="/spaces/:spaceId/tree"
          element={
            <RequireAuth>
              <SpaceBrowsePage />
            </RequireAuth>
          }
        />
        <Route
          path="/folders/:id"
          element={
            <RequireAuth>
              <FolderPage />
            </RequireAuth>
          }
        />
        <Route
          path="/spaces/:spaceId/graph"
          element={
            <RequireAuth>
              <GraphPage />
            </RequireAuth>
          }
        />
        <Route
          path="/spaces/:spaceId/content-health"
          element={
            <RequireAuth>
              <ContentHealthPage />
            </RequireAuth>
          }
        />
        <Route
          path="/team"
          element={
            <RequireAuth>
              <TeamPage />
            </RequireAuth>
          }
        />
        <Route
          path="/search"
          element={
            <RequireAuth>
              <SearchPage />
            </RequireAuth>
          }
        />
        <Route path="/access" element={<Navigate to="/spaces" replace />} />
        <Route
          path="/spaces/:spaceId/access"
          element={
            <RequireAuth>
              <AccessPage objectType="space" />
            </RequireAuth>
          }
        />
        <Route
          path="/folders/:folderId/access"
          element={
            <RequireAuth>
              <AccessPage objectType="folder" />
            </RequireAuth>
          }
        />
        <Route
          path="/documents/:documentId/access"
          element={
            <RequireAuth>
              <AccessPage objectType="document" />
            </RequireAuth>
          }
        />
      </Routes>
    </>
  )
}

export default function App() {
  return (
    <AuthProvider requireLogin>
      <AppShell />
    </AuthProvider>
  )
}
