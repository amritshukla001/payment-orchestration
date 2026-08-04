import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.jsx'
import Checkout from './Checkout.jsx'
import Merchant from './Merchant.jsx'

// A query param rather than a real route -- avoids needing history-API
// fallback configured on whatever eventually serves this as a static
// build, and a plain link (not client-side navigation) is enough since
// these are three genuinely separate views, not app state to preserve.
const view = new URLSearchParams(window.location.search).get('view')

function currentView() {
  if (view === 'checkout') return <Checkout />
  if (view === 'merchant') return <Merchant />
  return <App />
}

createRoot(document.getElementById('root')).render(
  <StrictMode>
    {currentView()}
  </StrictMode>,
)
