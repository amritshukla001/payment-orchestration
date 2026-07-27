import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.jsx'
import Checkout from './Checkout.jsx'

// A query param rather than a real route -- avoids needing history-API
// fallback configured on whatever eventually serves this as a static
// build, and a plain link (not client-side navigation) is enough since
// these are two genuinely separate views, not app state to preserve.
const isCheckout = new URLSearchParams(window.location.search).get('view') === 'checkout'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    {isCheckout ? <Checkout /> : <App />}
  </StrictMode>,
)
