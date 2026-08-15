/** @type {import('tailwindcss').Config} */
export default {
  darkMode: "class",
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        surface: {
          DEFAULT: "#0f1115",
          raised: "#171a21",
          border: "#262b36",
        },
        accent: {
          DEFAULT: "#5b8def",
          hover: "#4a7de0",
        },
      },
    },
  },
  plugins: [],
};
