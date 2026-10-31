import ReactDOM from 'react-dom/client';
import { I18nextProvider } from 'react-i18next';
import LocalOffice from './LocalOffice';
import { LiveOffice } from './LiveOffice';
import i18n from './officeI18n';
import localI18n from './officeLocalI18n';
import './officeLocal.css';
import './office.css';

const live = window.__PET_OFFICE_BOOTSTRAP__?.mode === 'live';
const pageI18n = live ? i18n : localI18n;
const language = localStorage.getItem('language') || window.__PET_OFFICE_BOOTSTRAP__?.language || window.__PET_OFFICE_LANGUAGE__;
if (language) void pageI18n.changeLanguage(language);
const root = document.getElementById('office-root');
if (!root) throw new Error('Office root is missing');
ReactDOM.createRoot(root).render(<I18nextProvider i18n={pageI18n}>{live ? <LiveOffice /> : <LocalOffice />}</I18nextProvider>);
