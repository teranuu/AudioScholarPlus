import React, { useEffect, useState } from 'react';
import axios from 'axios';
import { FiUpload } from 'react-icons/fi';
import { Link, useNavigate } from 'react-router-dom';
import { API_BASE_URL } from '../../services/authService';
import { Header } from '../Home/HomePage';

const Dashboard = () => {
  const [user, setUser] = useState(null);
  const [loadingUser, setLoadingUser] = useState(true);
  const [errorUser, setErrorUser] = useState(null);
  const navigate = useNavigate();

  useEffect(() => {
    const fetchUserData = async () => {
      setLoadingUser(true);
      setErrorUser(null);
      const token = localStorage.getItem('AuthToken');

      if (!token) {
        // setErrorUser('Not authenticated. Please log in.'); // Don't show error here, just redirect if no token
        setLoadingUser(false);
        navigate('/signin');
        return;
      }

      try {
        const response = await axios.get(`${API_BASE_URL}api/users/me`, {
          headers: {
            'Authorization': `Bearer ${token}`
          }
        });
        setUser(response.data);
      } catch (err) {
        console.error('Error fetching user profile for dashboard:', err);
        if (err.response && (err.response.status === 401 || err.response.status === 403)) {
          // setErrorUser('Session expired or unauthorized. Please log in again.');
          localStorage.removeItem('AuthToken');
          localStorage.removeItem('userId');
          navigate('/signin');
        } else {
          setErrorUser('Failed to load user information.');
        }
      } finally {
        setLoadingUser(false);
      }
    };

    fetchUserData();
  }, [navigate]);

  if (loadingUser) {
    return <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-gray-900 text-gray-700 dark:text-gray-200">Loading dashboard...</div>;
  }

  if (errorUser) {
    return <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-gray-900 text-red-600">{errorUser}</div>;
  }

  return (
    <div className="min-h-screen flex flex-col bg-gray-50 dark:bg-gray-900">

      <title>AudioScholar - Dashboard</title>
      <Header />

      <main className="flex-grow">
        <div className="container mx-auto px-4 py-12">
          <div className="max-w-6xl mx-auto">
            <h1 className="text-3xl font-bold text-gray-800 dark:text-gray-100 mb-10">Dashboard</h1>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
              <Link
                to="/upload"
                className="bg-white dark:bg-gray-800 rounded-lg shadow-md p-6 hover:shadow-xl transition-all duration-300 ease-in-out transform hover:-translate-y-1 cursor-pointer border border-gray-200 dark:border-gray-700"
              >

                <div className="flex items-center mb-4">
                  <div className="bg-teal-100 p-3 rounded-full mr-4">
                    <FiUpload className="h-6 w-6 text-teal-600" />
                  </div>
                  <h2 className="text-xl font-semibold text-gray-800 dark:text-gray-100">Upload Audio</h2>
                </div>
                <p className="text-gray-600 dark:text-gray-300">Upload new audio files to generate summaries and notes.</p>
              </Link>

              <Link
                to="/recordings"
                className="bg-white dark:bg-gray-800 rounded-lg shadow-md p-6 hover:shadow-xl transition-all duration-300 ease-in-out transform hover:-translate-y-1 cursor-pointer border border-gray-200 dark:border-gray-700"
              >

                <div className="flex items-center mb-4">
                  <div className="bg-blue-100 p-3 rounded-full mr-4">
                    <svg xmlns="http://www.w3.org/2000/svg" className="h-6 w-6 text-blue-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zM9 10l12-3" />
                    </svg>
                  </div>
                  <h2 className="text-xl font-semibold text-gray-800 dark:text-gray-100">Recording List</h2>
                </div>
                <p className="text-gray-600 dark:text-gray-300">View and manage all your audio recordings.</p>
              </Link>

              {user?.roles?.includes('ROLE_ADMIN') && (
                <Link
                  to="/admin"
                  className="bg-white dark:bg-gray-800 rounded-lg shadow-md p-6 hover:shadow-xl transition-all duration-300 ease-in-out transform hover:-translate-y-1 cursor-pointer border border-gray-200 dark:border-gray-700"
                >
                  <div className="flex items-center mb-4">
                    <div className="bg-purple-100 p-3 rounded-full mr-4">
                      <svg xmlns="http://www.w3.org/2000/svg" className="h-6 w-6 text-purple-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.125 1.125 0 011.962 1.096v1.455c0 .355.29.645.645.645h1.455c1.756.426 1.756 2.924 0 3.35a1.125 1.125 0 01-1.096 1.962h-1.455a.645.645 0 00-.645.645v1.455c-.426 1.756-2.924 1.756-3.35 0a1.125 1.125 0 01-1.962-1.096v-1.455a.645.645 0 00-.645-.645H4.317c-1.756-.426-1.756-2.924 0-3.35a1.125 1.125 0 011.096-1.962h1.455a.645.645 0 00.645-.645V4.317z" />
                      </svg>
                    </div>
                    <h2 className="text-xl font-semibold text-gray-800 dark:text-gray-100">Admin Dashboard</h2>
                  </div>
                  <p className="text-gray-600 dark:text-gray-300">Access administrative tools and user management.</p>
                </Link>
              )}
            </div>
          </div>
        </div>
      </main>



    </div>
  );
};

export default Dashboard;