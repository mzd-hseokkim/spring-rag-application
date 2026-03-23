import { useReducer, useCallback, useRef } from 'react';
import {
  startGeneration as apiStartGeneration,
  fetchTemplates,
  fetchJobs,
  getStreamUrl,
  type GenerationRequest,
  type GenerationJob,
  type GenerationProgressEvent,
  type DocumentTemplate,
  type GenerationStatus,
} from '@/api/generation';

// --- State ---

interface SectionProgress {
  index: number;
  title: string;
  status: 'pending' | 'generating' | 'complete';
}

interface GenerationState {
  templates: DocumentTemplate[];
  jobs: GenerationJob[];
  currentJob: GenerationJob | null;
  sections: SectionProgress[];
  statusMessage: string | null;
  isGenerating: boolean;
  error: string | null;
}

const initialState: GenerationState = {
  templates: [],
  jobs: [],
  currentJob: null,
  sections: [],
  statusMessage: null,
  isGenerating: false,
  error: null,
};

// --- Actions ---

type Action =
  | { type: 'SET_TEMPLATES'; payload: DocumentTemplate[] }
  | { type: 'SET_JOBS'; payload: GenerationJob[] }
  | { type: 'JOB_STARTED'; payload: GenerationJob }
  | { type: 'STATUS_CHANGED'; payload: { status: GenerationStatus; message: string } }
  | { type: 'PROGRESS_UPDATED'; payload: { currentSection: number; totalSections: number; sectionTitle: string } }
  | { type: 'GENERATION_COMPLETE'; payload: { downloadUrl: string } }
  | { type: 'GENERATION_ERROR'; payload: string }
  | { type: 'RESET' };

function reducer(state: GenerationState, action: Action): GenerationState {
  switch (action.type) {
    case 'SET_TEMPLATES':
      return { ...state, templates: action.payload };
    case 'SET_JOBS':
      return { ...state, jobs: action.payload };
    case 'JOB_STARTED':
      return {
        ...state,
        currentJob: action.payload,
        isGenerating: true,
        error: null,
        sections: [],
        statusMessage: '문서 생성을 시작합니다...',
      };
    case 'STATUS_CHANGED':
      return {
        ...state,
        statusMessage: action.payload.message,
        currentJob: state.currentJob
          ? { ...state.currentJob, status: action.payload.status }
          : null,
      };
    case 'PROGRESS_UPDATED': {
      const { currentSection, totalSections, sectionTitle } = action.payload;
      const sections: SectionProgress[] = [];
      for (let i = 1; i <= totalSections; i++) {
        const existing = state.sections.find(s => s.index === i);
        if (i < currentSection) {
          sections.push({ index: i, title: existing?.title || `섹션 ${i}`, status: 'complete' });
        } else if (i === currentSection) {
          sections.push({ index: i, title: sectionTitle, status: 'generating' });
        } else {
          sections.push({ index: i, title: existing?.title || `섹션 ${i}`, status: 'pending' });
        }
      }
      return {
        ...state,
        sections,
        currentJob: state.currentJob
          ? { ...state.currentJob, currentSection, totalSections }
          : null,
      };
    }
    case 'GENERATION_COMPLETE':
      return {
        ...state,
        isGenerating: false,
        statusMessage: null,
        sections: state.sections.map(s => ({ ...s, status: 'complete' as const })),
        currentJob: state.currentJob
          ? { ...state.currentJob, status: 'COMPLETE' }
          : null,
      };
    case 'GENERATION_ERROR':
      return {
        ...state,
        isGenerating: false,
        error: action.payload,
        currentJob: state.currentJob
          ? { ...state.currentJob, status: 'FAILED', errorMessage: action.payload }
          : null,
      };
    case 'RESET':
      return { ...state, currentJob: null, sections: [], statusMessage: null, isGenerating: false, error: null };
    default:
      return state;
  }
}

// --- Hook ---

export function useGeneration() {
  const [state, dispatch] = useReducer(reducer, initialState);
  const eventSourceRef = useRef<EventSource | null>(null);

  const loadTemplates = useCallback(async () => {
    const templates = await fetchTemplates();
    dispatch({ type: 'SET_TEMPLATES', payload: templates });
  }, []);

  const loadJobs = useCallback(async () => {
    const jobs = await fetchJobs();
    dispatch({ type: 'SET_JOBS', payload: jobs });
  }, []);

  const startGeneration = useCallback(async (request: GenerationRequest) => {
    try {
      const job = await apiStartGeneration(request);
      dispatch({ type: 'JOB_STARTED', payload: job });

      // SSE 구독
      const token = localStorage.getItem('accessToken');
      const url = getStreamUrl(job.id) + (token ? `?token=${token}` : '');
      const eventSource = new EventSource(url);
      eventSourceRef.current = eventSource;

      eventSource.addEventListener('status', (e) => {
        const data: GenerationProgressEvent = JSON.parse(e.data);
        dispatch({
          type: 'STATUS_CHANGED',
          payload: { status: data.status!, message: data.message! },
        });
      });

      eventSource.addEventListener('progress', (e) => {
        const data: GenerationProgressEvent = JSON.parse(e.data);
        dispatch({
          type: 'PROGRESS_UPDATED',
          payload: {
            currentSection: data.currentSection!,
            totalSections: data.totalSections!,
            sectionTitle: data.sectionTitle!,
          },
        });
      });

      eventSource.addEventListener('complete', (e) => {
        const data: GenerationProgressEvent = JSON.parse(e.data);
        dispatch({ type: 'GENERATION_COMPLETE', payload: { downloadUrl: data.downloadUrl! } });
        eventSource.close();
        eventSourceRef.current = null;
      });

      eventSource.addEventListener('error', () => {
        dispatch({ type: 'GENERATION_ERROR', payload: '연결이 끊어졌습니다.' });
        eventSource.close();
        eventSourceRef.current = null;
      });
    } catch (e) {
      dispatch({ type: 'GENERATION_ERROR', payload: e instanceof Error ? e.message : '생성 실패' });
    }
  }, []);

  const reset = useCallback(() => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }
    dispatch({ type: 'RESET' });
  }, []);

  return {
    ...state,
    loadTemplates,
    loadJobs,
    startGeneration,
    reset,
  };
}
