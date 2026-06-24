import React, { useState, useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Header } from '../Home/HomePage';

const CheckoutPage = () => {
    const [tier, setTier] = useState(null);
    const [paymentSummary, setPaymentSummary] = useState(null);
    const [isLoading, setIsLoading] = useState(false);
    const [showSuccessModal, setShowSuccessModal] = useState(false);
    const [successMessage, setSuccessMessage] = useState('');
    const [secondaryMessage, setSecondaryMessage] = useState('');
    const navigate = useNavigate();
    const location = useLocation();

    useEffect(() => {
        // Retrieve selected tier only; payment metadata is transient route state.
        const selectedTier = localStorage.getItem('selectedTier');
        const summary = location.state?.paymentSummary;

        localStorage.removeItem('paymentDetails');
        localStorage.removeItem('paymentMethod');

        if (!selectedTier) {
            console.error('Missing subscription tier. Redirecting...');
            navigate('/subscribe');
            return;
        }

        if (!summary) {
            console.error('Missing payment selection. Redirecting...');
            navigate('/payment');
            return;
        }

        setTier(selectedTier);
        setPaymentSummary(summary);

    }, [navigate, location.state]);

    const handleConfirm = () => {
        setIsLoading(true);

        // This checkout flow is intentionally non-authoritative. Premium role
        // changes must be granted only by an admin or trusted payment webhook
        // after server-side payment verification.
        setTimeout(() => {
            localStorage.setItem('pendingSubscriptionTier', tier);
            localStorage.removeItem('selectedTier');
            localStorage.removeItem('paymentMethod');

            setSuccessMessage('Subscription request received');
            setSecondaryMessage(
                'Your subscription is pending server-side verification. Premium access will be activated after payment is verified.'
            );
            setShowSuccessModal(true);
            setIsLoading(false);
        }, 1500);
    };

    const handleCloseSuccessModal = () => {
        setShowSuccessModal(false);
        navigate('/profile');
    };

    const getPaymentDisplay = () => {
        return paymentSummary?.displayName || 'Payment method selected';
    };

    const getPrice = () => {
        // In a real app, fetch this based on the tier
        // Updated price for Premium
        return tier === 'Premium' ? '₱150 / month' : 'Free';
    }

    if (!tier || !paymentSummary) {
        // Show loading or placeholder while retrieving data
        return (
            <div className="min-h-screen flex items-center justify-center bg-gray-100 dark:bg-gray-900 transition-colors duration-200">
                 <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-teal-500"></div>
            </div>
        );
    }

    return (
        <div className="min-h-screen flex flex-col bg-gray-100 dark:bg-gray-900 transition-colors duration-200">
            <title>AudioScholar - Checkout</title>
            <Header />

            <main className="flex-grow flex items-center justify-center py-12">
                <div className="container mx-auto px-4 sm:px-6 lg:px-8">
                    <div className="max-w-lg mx-auto bg-white dark:bg-gray-800 p-8 rounded-lg shadow-xl relative transition-colors duration-200">
                        <h1 className="text-2xl font-bold text-gray-800 dark:text-white mb-6 text-center">Confirm Your Subscription</h1>

                        <div className="space-y-4 mb-8">
                            <div className="flex justify-between items-center border-b border-gray-200 dark:border-gray-700 pb-2">
                                <span className="text-gray-600 dark:text-gray-300 font-medium">Plan:</span>
                                <span className="text-gray-800 dark:text-white font-semibold text-lg">{tier}</span>
                            </div>
                            <div className="flex justify-between items-center border-b border-gray-200 dark:border-gray-700 pb-2">
                                <span className="text-gray-600 dark:text-gray-300 font-medium">Price:</span>
                                <span className="text-gray-800 dark:text-white font-semibold text-lg">{getPrice()}</span>
                            </div>
                            <div className="flex justify-between items-center border-b border-gray-200 dark:border-gray-700 pb-2">
                                <span className="text-gray-600 dark:text-gray-300 font-medium">Payment Method:</span>
                                <span className="text-gray-800 dark:text-gray-200 text-sm text-right">{getPaymentDisplay()}</span>
                            </div>
                        </div>

                        <button 
                            onClick={handleConfirm}
                            disabled={isLoading}
                            className={`w-full py-3 px-4 rounded-md shadow-sm text-sm font-medium text-white transition-colors duration-200 transform hover:-translate-y-0.5 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-[#2D8A8A] ${
                                isLoading 
                                ? 'bg-gray-400 dark:bg-gray-600 cursor-not-allowed' 
                                : 'bg-[#2D8A8A] hover:bg-[#236b6b]'
                            }`}
                        >
                            {isLoading ? (
                                <span className="flex items-center justify-center">
                                    <svg className="animate-spin -ml-1 mr-3 h-5 w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                                        <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                                        <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                                    </svg>
                                    Processing...
                                </span>
                            ) : (
                                'Confirm and Pay'
                            )}
                        </button>

                        <button 
                            onClick={() => navigate('/payment')} // Go back to payment method selection
                            disabled={isLoading}
                            className="w-full mt-3 py-2 px-4 rounded-md text-sm font-medium text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-gray-300 dark:focus:ring-gray-600 transition-colors duration-200"
                        >
                            Change Payment Method
                        </button>
                    </div>
                    {showSuccessModal && (
                        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50 backdrop-blur-sm transition-opacity">
                            <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-2xl max-w-md w-full mx-4 p-8 animate-scale-in relative overflow-hidden transition-colors duration-200">
                                <div className="absolute top-0 left-0 w-full h-2 bg-gradient-to-r from-teal-400 to-teal-600"></div>
                                <div className="flex flex-col items-center text-center">
                                    <div className="w-16 h-16 rounded-full bg-teal-100 dark:bg-teal-900/30 flex items-center justify-center mb-6 shadow-inner">
                                        <svg className="w-8 h-8 text-teal-600 dark:text-teal-400" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2.5" d="M5 13l4 4L19 7" />
                                        </svg>
                                    </div>
                                    <h2 className="text-2xl font-bold text-gray-900 dark:text-white mb-2">{successMessage || 'Purchase Complete!'}</h2>
                                    <p className="text-gray-600 dark:text-gray-300 mb-8 leading-relaxed">
                                        {secondaryMessage || 'Your subscription request has been received and is pending verification.'}
                                    </p>
                                    <button
                                        onClick={handleCloseSuccessModal}
                                        className="w-full py-3 px-4 rounded-lg text-base font-semibold text-white bg-teal-600 hover:bg-teal-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-teal-500 transition-all duration-200 shadow-md hover:shadow-lg transform hover:-translate-y-0.5"
                                    >
                                        Go to Profile
                                    </button>
                                </div>
                            </div>
                        </div>
                    )}
                </div>
            </main>
        </div>
    );
};

export default CheckoutPage;