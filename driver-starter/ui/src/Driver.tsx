import { useState } from "react";
import { api } from "./client";

function Driver() {
  const [loading, setLoading] = useState(false);
  const [deviceInfo, setDeviceInfo] = useState("");
  const [connectResult, setConnectResult] = useState("");
  const [deviceId, setDeviceId] = useState("");
  const [disconnectResult, setDisconnectResult] = useState("");
  const [deviceStatus, setDeviceStatus] = useState<string>("");
  const [driverIdMap, setDriverIdMap] = useState<string>("");
  const [response, setResponse] = useState<string>("");

  const connect = async () => {
    setLoading(true);
    try {
      const res = await api.post("/balanced-connect-all", deviceInfo, {
        headers: {
          "Content-Type": "application/json",
        },
      });
      setConnectResult(JSON.stringify(res.data));
    } catch (e) {
      setConnectResult("error");
    } finally {
      setLoading(false);
    }
  };

  const disconnect = async () => {
    setLoading(true);
    try {
      const res = await api.delete("/disconnect", {
        data: deviceId,
        headers: {
          "Content-Type": "application/json",
        },
      });
      setDisconnectResult(JSON.stringify(res.data));
    } catch (e) {
      setDisconnectResult("error");
    } finally {
      setLoading(false);
    }
  };

  const getDeviceStatus = async () => {
    setLoading(true);
    try {
      const response = await api.get("/device-status");
      setDeviceStatus(JSON.stringify(response.data));
    } catch (error) {
      console.error(error);
      setDeviceStatus("error");
    } finally {
      setLoading(false);
    }
  };

  const getDriverIdMap = async () => {
    setLoading(true);
    try {
      const response = await api.get("/device-id-map");
      setDriverIdMap(JSON.stringify(response.data));
    } catch (error) {
      console.error(error);
      setDriverIdMap("error");
    } finally {
      setLoading(false);
    }
  };

  const getResponse = async () => {
    setLoading(true);
    try {
      const response = await api.get("/response");
      setResponse(JSON.stringify(response.data));
    } catch (error) {
      console.error(error);
      setResponse("error");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ padding: 20 }}>
      <div
        style={{
          marginBottom: 40,
          width: 800,
          border: "2px solid #ccc",
          borderRadius: 10,
          padding: 50,
        }}
      >
        <textarea
          style={{
            padding: 8,
            width: "100%",
            height: 80,
            fontSize: 16,
            resize: "vertical",
            overflow: "auto",
          }}
          value={deviceInfo}
          onChange={(e) => setDeviceInfo(e.target.value)}
        />
        <button
          style={{ marginLeft: 10, marginTop: 5 }}
          onClick={connect}
          disabled={loading}
        >
          connect
        </button>
        <pre
          style={{
            marginTop: 15,
            height: 80,
            border: "2px solid #aaa",
            borderRadius: 8,
            padding: 15,
            background: "#f8f8f8",
            fontSize: 14,
            whiteSpace: "pre-wrap",
            overflowX: "auto",
          }}
        >
          {connectResult}
        </pre>
      </div>
      <div
        style={{
          marginBottom: 40,
          width: 800,
          border: "2px solid #ccc",
          borderRadius: 10,
          padding: 50,
        }}
      >
        <textarea
          style={{
            padding: 8,
            width: "100%",
            height: 80,
            fontSize: 16,
            resize: "vertical",
            overflow: "auto",
          }}
          value={deviceId}
          onChange={(e) => setDeviceId(e.target.value)}
        />
        <button
          style={{ marginLeft: 10, marginTop: 5 }}
          onClick={disconnect}
          disabled={loading}
        >
          disconnect
        </button>
        <pre
          style={{
            marginTop: 15,
            height: 80,
            border: "2px solid #aaa",
            borderRadius: 8,
            padding: 15,
            background: "#f8f8f8",
            fontSize: 14,
            whiteSpace: "pre-wrap",
            overflowX: "auto",
          }}
        >
          {disconnectResult}
        </pre>
      </div>
      <div
        style={{
          marginBottom: 40,
          width: 800,
          border: "2px solid #ccc",
          borderRadius: 10,
          padding: 50,
        }}
      >
        <button onClick={getDeviceStatus} disabled={loading}>
          driver-status
        </button>
        <pre
          style={{
            marginTop: 15,
            height: 80,
            border: "2px solid #aaa",
            borderRadius: 8,
            padding: 15,
            background: "#f8f8f8",
            fontSize: 14,
            whiteSpace: "pre-wrap",
            overflowX: "auto",
          }}
        >
          {deviceStatus}
        </pre>
      </div>
      <div
        style={{
          marginBottom: 40,
          width: 800,
          border: "2px solid #ccc",
          borderRadius: 10,
          padding: 50,
        }}
      >
        <button onClick={getDriverIdMap} disabled={loading}>
          driver-id-map
        </button>
        <pre
          style={{
            marginTop: 15,
            height: 80,
            border: "2px solid #aaa",
            borderRadius: 8,
            padding: 15,
            background: "#f8f8f8",
            fontSize: 14,
            whiteSpace: "pre-wrap",
            overflowX: "auto",
          }}
        >
          {driverIdMap}
        </pre>
      </div>
      <div
        style={{
          marginBottom: 40,
          width: 800,
          border: "2px solid #ccc",
          borderRadius: 10,
          padding: 50,
        }}
      >
        <button onClick={getResponse} disabled={loading}>
          response
        </button>
        <pre
          style={{
            marginTop: 15,
            height: 80,
            border: "2px solid #aaa",
            borderRadius: 8,
            padding: 15,
            background: "#f8f8f8",
            fontSize: 14,
            whiteSpace: "pre-wrap",
            overflowX: "auto",
          }}
        >
          {response}
        </pre>
      </div>
    </div>
  );
}

export default Driver;
