import React from 'react';
import { Navigate } from 'react-router-dom';

const parseJwtRoles = (token) => {
    try {
        const [, payload] = token.split('.');
        if (!payload) return [];

        const normalizedPayload = payload.replace(/-/g, '+').replace(/_/g, '/');
        const paddedPayload = normalizedPayload.padEnd(
            normalizedPayload.length + ((4 - (normalizedPayload.length % 4)) % 4),
            '='
        );
        const decodedPayload = decodeURIComponent(
            atob(paddedPayload)
                .split('')
                .map((char) => `%${(`00${char.charCodeAt(0).toString(16)}`).slice(-2)}`)
                .join('')
        );
        const roles = JSON.parse(decodedPayload).roles;

        if (Array.isArray(roles)) return roles;
        if (typeof roles === 'string') {
            return roles.split(',').map((role) => role.trim()).filter(Boolean);
        }

        return [];
    } catch {
        console.warn('ProtectedRoute: Unable to read token roles; denying role-gated route.');
        return [];
    }
};

const ProtectedRoute = ({ children, requiredRole }) => {
    const token = localStorage.getItem('AuthToken');

    if (!token) {
        console.log('ProtectedRoute: No token found, redirecting to /signin');
        return <Navigate to="/signin" replace />;
    }

    if (requiredRole && !parseJwtRoles(token).includes(requiredRole)) {
        console.warn('ProtectedRoute: Required role missing, redirecting to /dashboard');
        return <Navigate to="/dashboard" replace />;
    }

    return children;
};

export default ProtectedRoute; 