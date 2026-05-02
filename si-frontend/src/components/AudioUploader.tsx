/**
 * 音频上传按钮组件
 */
import { useRef } from 'react'
import type { ChangeEvent } from 'react'

interface Props {
  accept?: string
  onChange: (file: File) => void
  label?: string
}

export default function AudioUploader({ accept = 'audio/*', onChange, label }: Props) {
  const inputRef = useRef<HTMLInputElement>(null)

  const handleChange = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (file) {
      onChange(file)
      // 重置 input，允许重复选择同一文件
      if (inputRef.current) {
        inputRef.current.value = ''
      }
    }
  }

  return (
    <>
      <input
        ref={inputRef}
        type="file"
        accept={accept}
        onChange={handleChange}
        style={{ display: 'none' }}
      />
      <button
        className="btn-upload"
        onClick={() => inputRef.current?.click()}
        type="button"
      >
        {label ?? '选择音频文件'}
      </button>
    </>
  )
}
