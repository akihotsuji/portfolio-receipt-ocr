import { createBrowserRouter, RouterProvider } from "react-router-dom";

const router = createBrowserRouter(
  [
    {
      path: "/",
      element: (
        <div style={{ padding: "2rem", textAlign: "center" }}>
          <h1 style={{ fontSize: "1.5rem", fontWeight: "bold", marginBottom: "1rem" }}>
            領収書 OCR
          </h1>
          <p style={{ color: "#6b7280" }}>設計準備中 - 要件定義・基本設計は後日実施</p>
        </div>
      ),
    },
  ],
  {
    basename: "receipt-ocr",
  },
);

function App() {
  return <RouterProvider router={router} />;
}

export default App;
