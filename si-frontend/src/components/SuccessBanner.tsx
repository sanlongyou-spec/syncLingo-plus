/**
 * 成功提示横幅组件
 */
interface Props {
  message: string
  children?: React.ReactNode
}

export default function SuccessBanner({ message, children }: Props) {
  if (!message) return null
  return (
    <div className="success-banner" role="status">
      <span className="success-banner__message">{message}</span>
      {children}
    </div>
  )
}
