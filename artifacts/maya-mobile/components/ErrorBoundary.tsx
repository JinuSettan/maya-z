import React from 'react';
import { ErrorFallback } from '@/components/ErrorFallback';

type Props = { children: React.ReactNode };
type State = { error: Error | null };

export class ErrorBoundary extends React.Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  render() {
    return this.state.error ? <ErrorFallback error={this.state.error} /> : this.props.children;
  }
}