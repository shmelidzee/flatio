import { useEffect, useState } from "react";
import type { ReactElement } from "react";
import { useNavigate } from "react-router-dom";
import { TelegramLoginWidget } from "../auth/TelegramLoginWidget";
import {
  TelegramLoginError,
  fetchTelegramBotUsername,
  loginWithTelegramWidget,
} from "../auth/telegramAuth";
import type { TelegramAuthUser } from "../auth/telegramAuth";
import { setToken } from "../auth/token";

const ERROR_MESSAGES: Record<"not-admin" | "invalid", string> = {
  "not-admin": "У этого Telegram-аккаунта нет доступа к админ-панели.",
  invalid: "Не удалось войти через Telegram. Попробуйте ещё раз.",
};

const BOT_USERNAME_FETCH_FAILED = "Не удалось загрузить виджет входа. Обновите страницу.";

export function LoginPage(): ReactElement {
  const navigate = useNavigate();
  const [botUsername, setBotUsername] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchTelegramBotUsername()
      .then(setBotUsername)
      .catch(() => setError(BOT_USERNAME_FETCH_FAILED));
  }, []);

  async function handleAuth(user: TelegramAuthUser): Promise<void> {
    setError(null);
    try {
      const { accessToken } = await loginWithTelegramWidget(user);
      setToken(accessToken);
      navigate("/", { replace: true });
    } catch (err) {
      const reason = err instanceof TelegramLoginError ? err.message : "invalid";
      setError(ERROR_MESSAGES[reason as "not-admin" | "invalid"]);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface text-white">
      <div className="rounded-lg border border-surface-border bg-surface-raised p-8 text-center">
        <h1 className="text-xl font-semibold">Flatio Admin</h1>
        <p className="mt-2 text-sm text-gray-400">Войдите через Telegram, чтобы продолжить.</p>

        <div className="mt-6 flex justify-center">
          {botUsername && <TelegramLoginWidget botUsername={botUsername} onAuth={handleAuth} />}
        </div>

        {error && <p className="mt-4 text-sm text-red-400">{error}</p>}
      </div>
    </div>
  );
}
