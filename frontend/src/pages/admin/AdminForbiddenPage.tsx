// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { Link } from 'react-router-dom'

/** 403 administration — sans fuite de données sensibles. */
export function AdminForbiddenPage() {
  return (
    <div className="admin-page" data-testid="admin-forbidden">
      <div className="admin-page__breadcrumb">
        <div className="admin-page__breadcrumb-trail">
          <span className="admin-page__breadcrumb-current">Administration</span>
        </div>
        <Link to="/" className="admin-page__home-link">
          Retour à l&apos;accueil
        </Link>
      </div>
      <main className="admin-main">
        <div className="admin-main__inner">
          <h1 className="admin-title">Accès refusé</h1>
          <p className="admin-lead" role="alert">
            Administrateur système requis pour accéder à l&apos;administration de la plateforme.
          </p>
          <Link to="/account" className="admin-cta admin-cta--ghost">
            Paramètres du compte
          </Link>
        </div>
      </main>
    </div>
  )
}
