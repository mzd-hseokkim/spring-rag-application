export interface DocumentTag {
  id: string;
  name: string;
}

export interface DocumentCollection {
  id: string;
  name: string;
  description?: string;
  createdAt: string;
}

export interface Document {
  id: string;
  filename: string;
  contentType: string;
  fileSize: number;
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  errorMessage: string | null;
  chunkCount: number;
  isPublic: boolean;
  tags: DocumentTag[];
  collections: DocumentCollection[];
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

export interface Conversation {
  id: string;
  sessionId: string;
  title: string | null;
  modelName: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ConversationDetail {
  conversation: Conversation;
  messages: { role: 'user' | 'assistant'; content: string; timestamp: string; sources?: Source[] }[];
}

export interface User {
  id: string;
  email: string;
  name: string;
  role: 'USER' | 'ADMIN';
  avatarUrl?: string | null;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  user: User;
}
