import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ReferenceArea,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import type { DeviceThreshold } from '../api/types';
import type { ChartPoint } from '../domain/status';
import { formatClock, formatNumber } from '../domain/format';

interface Props {
  points: ChartPoint[];
  threshold: DeviceThreshold | undefined;
  metric: 'temperature' | 'humidity';
}

const CONFIG = {
  temperature: {
    label: 'Temperature',
    unit: '°C',
    color: 'var(--chart-temp)',
    lower: 'temperatureLowerC',
    upper: 'temperatureUpperC',
  },
  humidity: {
    label: 'Humidity',
    unit: '%RH',
    color: 'var(--chart-hum)',
    lower: 'humidityLowerPct',
    upper: 'humidityUpperPct',
  },
} as const;

/** One metric over time with the device's allowed band shaded, so excursions are visible at a glance. */
export function TrendChart({ points, threshold, metric }: Props) {
  const c = CONFIG[metric];
  const lower = threshold?.[c.lower] ?? undefined;
  const upper = threshold?.[c.upper] ?? undefined;
  const values = points.map((p) => p[metric]).filter((v): v is number => v !== undefined);

  if (values.length === 0) {
    return <div className="chart-empty">No {c.label.toLowerCase()} readings in this window.</div>;
  }

  // Keep the limits on screen even when every reading sits well inside them.
  const all = [...values, ...(lower !== undefined ? [lower] : []), ...(upper !== undefined ? [upper] : [])];
  const min = Math.min(...all);
  const max = Math.max(...all);
  const pad = Math.max((max - min) * 0.15, 0.5);

  return (
    <div className="chart" data-testid={`chart-${metric}`}>
      <ResponsiveContainer width="100%" height={240}>
        <LineChart data={points} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
          <CartesianGrid stroke="var(--grid)" vertical={false} />
          <XAxis
            dataKey="time"
            type="number"
            scale="time"
            domain={['dataMin', 'dataMax']}
            tickFormatter={formatClock}
            stroke="var(--text-muted)"
            fontSize={12}
            minTickGap={40}
          />
          <YAxis
            domain={[Math.floor((min - pad) * 10) / 10, Math.ceil((max + pad) * 10) / 10]}
            stroke="var(--text-muted)"
            fontSize={12}
            width={48}
            tickFormatter={(v: number) => formatNumber(v)}
          />
          {lower !== undefined && upper !== undefined && (
            <ReferenceArea y1={lower} y2={upper} fill="var(--band)" fillOpacity={1} ifOverflow="extendDomain" />
          )}
          <Tooltip
            labelFormatter={(v) => formatClock(Number(v))}
            formatter={(v) => [`${formatNumber(Number(v), 2)} ${c.unit}`, c.label]}
            contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 8 }}
          />
          <Legend />
          <Line
            type="monotone"
            dataKey={metric}
            name={`${c.label} (${c.unit})`}
            stroke={c.color}
            strokeWidth={2}
            dot={false}
            isAnimationActive={false}
            connectNulls
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
