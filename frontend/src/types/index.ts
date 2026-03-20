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

export interface AgentStep {
  step: string;
  message: string;
}

export interface Message {
  role: 'user' | 'assistant';
  content: string;
  sources?: Source[];
  agentSteps?: AgentStep[];
}

export interface LlmModel {
  id: string;
  provider: 'OLLAMA' | 'ANTHROPIC';
  modelId: string;
  displayName: string;
  purpose: 'CHAT' | 'EMBEDDING' | 'QUERY' | 'RERANK' | 'EVALUATION';
  isDefault: boolean;
  isActive: boolean;
  baseUrl: string | null;
  apiKeyRef: string | null;
  temperature: number | null;
  maxTokens: number | null;
}

export interface DiscoveredModel {
  modelId: string;
  size: number;
  modifiedAt: string;
}
