import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { APP_NAME } from './appName.ts'

// The single point that actually sets the browser tab title, so the static <title> in
// index.html (which cannot import a TS module) is never the authority - see appName.ts.
document.title = APP_NAME

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
