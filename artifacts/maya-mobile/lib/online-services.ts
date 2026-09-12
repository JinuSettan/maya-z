export type ServiceStatus = 'not_configured' | 'available';

export type OnlineServiceStatus = {
  status: ServiceStatus;
  message: string;
};

export type MayaOnlineService = {
  askLargerModel(input: string): Promise<OnlineServiceStatus>;
  generateImage(prompt: string): Promise<OnlineServiceStatus>;
  generateVideo(prompt: string): Promise<OnlineServiceStatus>;
  startVoiceCall(): Promise<OnlineServiceStatus>;
  syncCloudData(): Promise<OnlineServiceStatus>;
};

const notConfigured = (feature: string): OnlineServiceStatus => ({
  status: 'not_configured',
  message: `${feature} is not configured. MAYA is staying local.`
});

export const mayaOnlineService: MayaOnlineService = {
  async askLargerModel() {
    return notConfigured('Online AI');
  },
  async generateImage() {
    return notConfigured('Image generation');
  },
  async generateVideo() {
    return notConfigured('Video generation');
  },
  async startVoiceCall() {
    return notConfigured('Voice calls');
  },
  async syncCloudData() {
    return notConfigured('Cloud sync');
  }
};

export type FirebaseFoundationStatus = {
  configured: boolean;
  message: string;
};

export function getFirebaseFoundationStatus(): FirebaseFoundationStatus {
  const hasPublicProjectId = Boolean(process.env.EXPO_PUBLIC_FIREBASE_PROJECT_ID);
  return hasPublicProjectId
    ? { configured: true, message: 'Public Firebase project settings are present; server credentials remain server-side.' }
    : { configured: false, message: 'Firebase is not configured for this build.' };
}