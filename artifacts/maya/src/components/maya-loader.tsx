import { motion } from 'framer-motion';
import logo from '../assets/maya-logo.png';

interface MayaLoaderProps {
  label?: string;
  fullScreen?: boolean;
}

/**
 * Premium full-screen brand moment shown on cold start and while the app
 * bootstraps its first conversation. Also used (non-fullscreen) as a
 * lightweight inline loader for secondary transitions.
 */
export function MayaLoader({ label = 'Waking up Maya', fullScreen = true }: MayaLoaderProps) {
  return (
    <div
      className={`flex flex-col items-center justify-center gap-6 bg-background ${
        fullScreen ? 'fixed inset-0 z-[100] h-[100dvh] w-full' : ''
      }`}
    >
      <div className="relative flex items-center justify-center">
        <motion.div
          className="absolute h-28 w-28 rounded-full bg-primary/25 blur-2xl"
          animate={{ scale: [0.85, 1.15, 0.85], opacity: [0.5, 0.9, 0.5] }}
          transition={{ duration: 2.4, repeat: Infinity, ease: 'easeInOut' }}
        />
        <motion.img
          src={logo}
          alt="Maya"
          className="relative h-20 w-20 md:h-24 md:w-24 object-contain drop-shadow-[0_0_24px_rgba(255,120,40,0.45)]"
          initial={{ opacity: 0, scale: 0.7, rotate: -8 }}
          animate={{ opacity: 1, scale: 1, rotate: 0 }}
          transition={{ duration: 0.8, ease: [0.16, 1, 0.3, 1] }}
        />
        <motion.div
          className="absolute h-32 w-32 md:h-36 md:w-36 rounded-full border border-primary/30"
          animate={{ scale: [1, 1.3], opacity: [0.6, 0] }}
          transition={{ duration: 1.8, repeat: Infinity, ease: 'easeOut' }}
        />
      </div>

      <motion.div
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.3, duration: 0.5 }}
        className="flex flex-col items-center gap-1.5"
      >
        <h1 className="text-2xl md:text-3xl font-bold tracking-tight text-foreground">
          MAYA<span className="text-primary">.</span>
        </h1>
        <div className="flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.3em] text-muted-foreground">
          <span>{label}</span>
          <motion.span
            animate={{ opacity: [0.2, 1, 0.2] }}
            transition={{ duration: 1.2, repeat: Infinity }}
          >
            …
          </motion.span>
        </div>
      </motion.div>
    </div>
  );
}
