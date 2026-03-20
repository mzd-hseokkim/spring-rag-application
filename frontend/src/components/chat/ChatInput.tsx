import { useState } from 'react';

interface Props {
  onSend: (message: string) => void;
  disabled: boolean;
}

export function ChatInput({ onSend, disabled }: Props) {
  const [input, setInput] = useState('');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!input.trim() || disabled) return;
    onSend(input.trim());
    setInput('');
  };

  return (
    <form className="chat-input" onSubmit={handleSubmit}>
      <input
        type="text"
        value={input}
        onChange={e => setInput(e.target.value)}
        placeholder="질문을 입력하세요..."
        disabled={disabled}
      />
      <button type="submit" disabled={disabled || !input.trim()}>
        전송
      </button>
    </form>
  );
}
