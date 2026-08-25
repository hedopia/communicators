import { useMemo, useRef } from "react";
import type { KeyboardEvent } from "react";

interface CodeEditorProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  minHeight?: number;
}

function CodeEditor({
  label,
  value,
  onChange,
  placeholder,
  minHeight = 180,
}: CodeEditorProps) {
  const gutterRef = useRef<HTMLPreElement>(null);
  const lineCount = useMemo(
    () => Math.max(1, value.split("\n").length),
    [value]
  );
  const lineNumbers = useMemo(
    () => Array.from({ length: lineCount }, (_, index) => index + 1).join("\n"),
    [lineCount]
  );

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key !== "Tab") return;
    event.preventDefault();

    const textarea = event.currentTarget;
    const start = textarea.selectionStart;
    const end = textarea.selectionEnd;
    const indent = "    ";
    const nextValue = value.slice(0, start) + indent + value.slice(end);
    onChange(nextValue);
    requestAnimationFrame(() => {
      textarea.selectionStart = start + indent.length;
      textarea.selectionEnd = start + indent.length;
    });
  };

  return (
    <div className="code-editor-field">
      <div className="code-editor-title">
        <span>{label}</span>
        <span className="language-badge">Python</span>
      </div>
      <div className="code-editor-shell">
        <pre ref={gutterRef} className="code-editor-gutter" aria-hidden="true">
          {lineNumbers}
        </pre>
        <textarea
          className="code-editor-input"
          aria-label={label}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={handleKeyDown}
          onScroll={(event) => {
            if (gutterRef.current) {
              gutterRef.current.scrollTop = event.currentTarget.scrollTop;
            }
          }}
          placeholder={placeholder}
          style={{ minHeight }}
          spellCheck={false}
          autoCapitalize="off"
          autoCorrect="off"
        />
      </div>
      <div className="code-editor-status">
        {lineCount} lines · Tab 키로 4칸 들여쓰기
      </div>
    </div>
  );
}

export default CodeEditor;
