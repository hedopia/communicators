import { useState } from "react";
import DevicesTab from "./DevicesTab";
import CommandsTab from "./CommandsTab";
import NodesTab from "./NodesTab";
import ResponsesTab from "./ResponsesTab";
import "./App.css";

const TABS = ["Devices", "Commands", "Nodes", "Responses"] as const;
type Tab = (typeof TABS)[number];

function App() {
  const [tab, setTab] = useState<Tab>("Devices");

  return (
    <div className="app">
      <header className="app-header">
        <h1>communicators driver</h1>
        <nav className="tabs">
          {TABS.map((name) => (
            <button
              key={name}
              className={tab === name ? "tab active" : "tab"}
              onClick={() => setTab(name)}
            >
              {name}
            </button>
          ))}
        </nav>
      </header>
      <main>
        {tab === "Devices" && <DevicesTab />}
        {tab === "Commands" && <CommandsTab />}
        {tab === "Nodes" && <NodesTab />}
        {tab === "Responses" && <ResponsesTab />}
      </main>
    </div>
  );
}

export default App;
