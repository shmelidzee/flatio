import type { ReactElement } from "react";

interface PlaceholderPageProps {
  title: string;
}

export function PlaceholderPage({ title }: PlaceholderPageProps): ReactElement {
  return (
    <div>
      <h1 className="text-xl font-semibold">{title}</h1>
      <p className="mt-2 text-sm text-gray-400">Раздел в разработке.</p>
    </div>
  );
}
