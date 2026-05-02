/**
 * 导航头组件
 */
interface Props {
  title: string
  actions?: React.ReactNode
}

export default function Header({ title, actions }: Props) {
  return (
    <header className="header">
      <h1 className="header__title">{title}</h1>
      {actions && <div className="header__actions">{actions}</div>}
    </header>
  )
}
