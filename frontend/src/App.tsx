import { ChatView } from './components/chat/ChatView';
import { DocumentUpload } from './components/document/DocumentUpload';
import { DocumentList } from './components/document/DocumentList';
import { useDocuments } from './hooks/useDocuments';
import './App.css';

function App() {
  const { documents, uploading, upload } = useDocuments();

  return (
    <div className="app">
      <aside className="sidebar">
        <h2>문서 관리</h2>
        <DocumentUpload onUpload={upload} uploading={uploading} />
        <DocumentList documents={documents} />
      </aside>
      <main className="main">
        <ChatView />
      </main>
    </div>
  );
}

export default App;
