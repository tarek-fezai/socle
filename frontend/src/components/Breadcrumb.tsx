// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { Fragment } from 'react'
import { Link } from 'react-router-dom'
import type { BreadcrumbItem } from '../lib/folders'

/** Fil d'Ariane (classe `.breadcrumb`) : le dernier élément est le courant, non cliquable. */
export function Breadcrumb({
  items,
  className = '',
}: {
  items: BreadcrumbItem[]
  className?: string
}) {
  return (
    <nav aria-label="Fil d'Ariane" className={`breadcrumb ${className}`.trim()}>
      {items.map((item, i) => {
        const last = i === items.length - 1
        return (
          <Fragment key={`${i}-${item.label}`}>
            {i > 0 && (
              <span className="text-[#DEDEE1]" aria-hidden>
                →
              </span>
            )}
            {item.to && !last ? (
              <Link to={item.to}>{item.label}</Link>
            ) : (
              <span
                className={last ? 'font-medium text-socle-ink' : undefined}
                aria-current={last ? 'page' : undefined}
              >
                {item.label}
              </span>
            )}
          </Fragment>
        )
      })}
    </nav>
  )
}
