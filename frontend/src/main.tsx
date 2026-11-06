// Entry point: read the runtime config (config.json, written by the nginx container), then render the dashboard.
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import { loadConfig } from './config';
import './styles.css';

void loadConfig().then((config) => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App config={config} />
    </StrictMode>,
  );
});
