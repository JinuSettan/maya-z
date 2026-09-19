import { initializeApp, getApps, getApp } from 'firebase/app';
import { getAuth } from 'firebase/auth';
import { getFirestore } from 'firebase/firestore';

const firebaseConfig = {
  apiKey: "AIzaSyA7rza1tg74cICCLIu3XQv8Kg8JB9s70yw",
  authDomain: "mayaxrocky.firebaseapp.com",
  projectId: "mayaxrocky",
  storageBucket: "mayaxrocky.firebasestorage.app",
  messagingSenderId: "522458653105",
  appId: "1:522458653105:web:f000e5c291ed271a761d90",
  measurementId: "G-KS1B4VCGPM",
};

const app = getApps().length > 0
  ? getApp()
  : initializeApp(firebaseConfig);

export const auth = getAuth(app);
export const db = getFirestore(app);
export default app;
