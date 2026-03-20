export interface Document {
  id: string;
  filename: string;
  contentType: string;
  fileSize: number;
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  errorMessage: string | null;
  chunkCount: number;
  createdAt: string;
}

export interface Source {
  documentId: string;
  filename: string;
  chunkIndex: number;
  excerpt: string;
}

export interface Message {
  role: 'user' | 'assistant';
  content: string;
  sources?: Source[];
}
