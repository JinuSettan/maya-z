import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Route, Switch, Router as WouterRouter } from 'wouter';
import ChatPage from './pages/chat';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      staleTime: 1000 * 60,
      retry: 1,
    },
  },
});

function Router() {
  return (
    <Switch>
      <Route path="/" component={ChatPage} />
      <Route path="/:id" component={ChatPage} />
    </Switch>
  );
}

function App() {
  // Ensure we are in dark mode by applying the class to document element
  if (typeof document !== 'undefined') {
    document.documentElement.classList.add('dark');
  }

  return (
    <QueryClientProvider client={queryClient}>
      <WouterRouter base={import.meta.env.BASE_URL.replace(/\/$/, '')}>
        <div className="noise-bg" />
        <Router />
      </WouterRouter>
    </QueryClientProvider>
  );
}

export default App;
