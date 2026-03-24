import { useReducer, useCallback, useRef } from 'react';
import {
  startQuestionnaire as apiStartQuestionnaire,
  fetchQuestionnaireJobs,
  deleteQuestionnaireJob as apiDeleteJob,
  getQuestionnaireStreamUrl,
  type QuestionnaireRequest,
  type QuestionnaireJob,
  type QuestionnaireProgressEvent,
  type QuestionnaireStatus,
} from '@/api/questionnaire';

// --- State ---

interface PersonaProgress {
  index: number;
  name: string;
  status: 'pending' | 'generating' | 'complete';
}

interface QuestionnaireState {
  jobs: QuestionnaireJob[];
  currentJob: QuestionnaireJob | null;
  personas: PersonaProgress[];
  statusMessage: string | null;
  isGenerating: boolean;
  error: string | null;
}

const initialState: QuestionnaireState = {
  jobs: [],
  currentJob: null,
  personas: [],
  statusMessage: null,
  isGenerating: false,
  error: null,
};

// --- Actions ---

type Action =
  | { type: 'SET_JOBS'; payload: QuestionnaireJob[] }
  | { type: 'JOB_STARTED'; payload: QuestionnaireJob }
  | { type: 'STATUS_CHANGED'; payload: { status: QuestionnaireStatus; message: string } }
  | { type: 'PROGRESS_UPDATED'; payload: { currentPersona: number; totalPersonas: number; personaName: string } }
  | { type: 'GENERATION_COMPLETE'; payload: { downloadUrl: string } }
  | { type: 'GENERATION_ERROR'; payload: string }
  | { type: 'RESET' };

function reducer(state: QuestionnaireState, action: Action): QuestionnaireState {
  switch (action.type) {
    case 'SET_JOBS':
      return { ...state, jobs: action.payload };
    case 'JOB_STARTED':
      return {
        ...state,
        currentJob: action.payload,
        isGenerating: true,
        error: null,
        personas: [],
        statusMessage: '질의서 생성을 시작합니다...',
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
      const { currentPersona, totalPersonas, personaName } = action.payload;
      const personas: PersonaProgress[] = [];
      for (let i = 1; i <= totalPersonas; i++) {
        const existing = state.personas.find(p => p.index === i);
        if (i < currentPersona) {
          personas.push({ index: i, name: existing?.name || `페르소나 ${i}`, status: 'complete' });
        } else if (i === currentPersona) {
          personas.push({ index: i, name: personaName, status: 'generating' });
        } else {
          personas.push({ index: i, name: existing?.name || `페르소나 ${i}`, status: 'pending' });
        }
      }
      return {
        ...state,
        personas,
        currentJob: state.currentJob
          ? { ...state.currentJob, currentPersona, totalPersonas }
          : null,
      };
    }
    case 'GENERATION_COMPLETE':
      return {
        ...state,
        isGenerating: false,
        statusMessage: null,
        personas: state.personas.map(p => ({ ...p, status: 'complete' as const })),
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
      return { ...state, currentJob: null, personas: [], statusMessage: null, isGenerating: false, error: null };
    default:
      return state;
  }
}

// --- Hook ---

export function useQuestionnaire() {
  const [state, dispatch] = useReducer(reducer, initialState);
  const eventSourceRef = useRef<EventSource | null>(null);

  const loadJobs = useCallback(async () => {
    const jobs = await fetchQuestionnaireJobs();
    dispatch({ type: 'SET_JOBS', payload: jobs });
  }, []);

  const startGeneration = useCallback(async (request: QuestionnaireRequest) => {
    try {
      const job = await apiStartQuestionnaire(request);
      dispatch({ type: 'JOB_STARTED', payload: job });

      const token = localStorage.getItem('accessToken');
      const url = getQuestionnaireStreamUrl(job.id) + (token ? `?token=${token}` : '');
      const eventSource = new EventSource(url);
      eventSourceRef.current = eventSource;

      eventSource.addEventListener('status', (e) => {
        const data: QuestionnaireProgressEvent = JSON.parse(e.data);
        dispatch({
          type: 'STATUS_CHANGED',
          payload: { status: data.status!, message: data.message! },
        });
      });

      eventSource.addEventListener('progress', (e) => {
        const data: QuestionnaireProgressEvent = JSON.parse(e.data);
        dispatch({
          type: 'PROGRESS_UPDATED',
          payload: {
            currentPersona: data.currentPersona!,
            totalPersonas: data.totalPersonas!,
            personaName: data.personaName!,
          },
        });
      });

      eventSource.addEventListener('complete', (e) => {
        const data: QuestionnaireProgressEvent = JSON.parse(e.data);
        dispatch({ type: 'GENERATION_COMPLETE', payload: { downloadUrl: data.downloadUrl! } });
        eventSource.close();
        eventSourceRef.current = null;
        // 목록 갱신
        fetchQuestionnaireJobs().then(jobs => dispatch({ type: 'SET_JOBS', payload: jobs })).catch(() => {});
      });

      eventSource.addEventListener('error', (e) => {
        try {
          const data: QuestionnaireProgressEvent = JSON.parse((e as MessageEvent).data);
          dispatch({ type: 'GENERATION_ERROR', payload: data.message || '생성 중 오류가 발생했습니다.' });
        } catch {
          dispatch({ type: 'GENERATION_ERROR', payload: '생성 중 오류가 발생했습니다.' });
        }
        eventSource.close();
        eventSourceRef.current = null;
      });

      eventSource.onerror = () => {
        if (eventSource.readyState === EventSource.CLOSED) {
          return;
        }
        dispatch({ type: 'GENERATION_ERROR', payload: '서버와의 연결이 끊어졌습니다.' });
        eventSource.close();
        eventSourceRef.current = null;
      };
    } catch (e) {
      dispatch({ type: 'GENERATION_ERROR', payload: e instanceof Error ? e.message : '생성 실패' });
    }
  }, []);

  const removeJob = useCallback(async (jobId: string) => {
    await apiDeleteJob(jobId);
    dispatch({ type: 'SET_JOBS', payload: state.jobs.filter(j => j.id !== jobId) });
  }, [state.jobs]);

  const reset = useCallback(() => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }
    dispatch({ type: 'RESET' });
  }, []);

  return {
    ...state,
    loadJobs,
    startGeneration,
    removeJob,
    reset,
  };
}
