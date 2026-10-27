import ReactDOM from 'react-dom/client';
import LocalOffice from './LocalOffice';
import { LiveOffice } from './LiveOffice';
import i18n from './officeI18n';
import './officeLocal.css';
import './office.css';

const language = localStorage.getItem('language') || window.__PET_OFFICE_BOOTSTRAP__?.language;
if (language) void i18n.changeLanguage(language);
const root = document.getElementById('office-root');
if (!root) throw new Error('Office root is missing');
ReactDOM.createRoot(root).render(window.__PET_OFFICE_BOOTSTRAP__?.mode === 'live' ? <LiveOffice /> : <LocalOffice />);
