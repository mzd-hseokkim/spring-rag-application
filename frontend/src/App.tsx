import { useState } from 'react';
import { ChatView } from './components/chat/ChatView';
import { DocumentUpload } from './components/document/DocumentUpload';
import { DocumentList } from './components/document/DocumentList';
import { ModelManagement } from './components/model/ModelManagement';
import { useDocuments } from './hooks/useDocuments';
import './App.css';

function App() {
  const { documents, uploading, upload } = useDocuments();
  const [sidebarTab, setSidebarTab] = useState<'documents' | 'models'>('documents');

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="sidebar-tabs">
          <button
            className={`sidebar-tab ${sidebarTab === 'documents' ? 'active' : ''}`}
            onClick={() => setSidebarTab('documents')}
          >
            문서
          </button>
          <button
            className={`sidebar-tab ${sidebarTab === 'models' ? 'active' : ''}`}
            onClick={() => setSidebarTab('models')}
          >
            모델
          </button>
        </div>

        {sidebarTab === 'documents' ? (
          <>
            <DocumentUpload onUpload={upload} uploading={uploading} />
            <DocumentList documents={documents} />
          </>
        ) : (
          <ModelManagement />
        )}
      </aside>
      <main className="main">
        <ChatView />
      </main>
    </div>
  );
}

export default App;
