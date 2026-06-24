import { API_BASE_URL as AUTH_API_BASE } from './authService';
import axios from 'axios';

const API_BASE_URL = `${AUTH_API_BASE}api/audio/recordings`;

const getAuthHeaders = () => {
  const token = localStorage.getItem('AuthToken');
  return {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`,
  };
};

export const recordingService = {
  retryProcessing: async (recordingId) => {
    try {
      const response = await axios.post(`${API_BASE_URL}/${recordingId}/retry-processing`, {}, {
        headers: getAuthHeaders()
      });
      return response.data;
    } catch (error) {
      console.error("Error retrying processing:", error);
      throw error;
    }
  },

  toggleFavorite: async (recordingId) => {
    try {
      const response = await axios.post(`${API_BASE_URL}/${recordingId}/favorite`, {}, {
        headers: getAuthHeaders()
      });
      return response.data;
    } catch (error) {
        console.error("Error toggling favorite:", error);
        throw error;
    }
  }
};
