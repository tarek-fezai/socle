// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { PublicBrandingProvider } from './lib/publicBranding'
import './index.css'
import { applyDevicePreferences } from './lib/devicePreferences'

applyDevicePreferences()

const queryClient = new QueryClient()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <PublicBrandingProvider>
          <App />
        </PublicBrandingProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
