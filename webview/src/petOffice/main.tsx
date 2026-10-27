import ReactDOM from 'react-dom/client';
import LocalOffice from './LocalOffice';
import './officeLocalI18n';
import './officeLocal.css';

const root = document.getElementById('office-root');
if (!root) throw new Error('Office root is missing');
ReactDOM.createRoot(root).render(<LocalOffice />);
