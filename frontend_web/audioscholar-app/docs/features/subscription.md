# Subscription System

The Subscription System allows users to upgrade their accounts to unlock premium features. It includes a multi-step flow from plan selection to payment and checkout.

## Overview

-   **Tiers:** Basic (Free) and Premium (Paid).
-   **Payment Methods:** Credit/Debit Card and E-Wallet (GCash/PayMaya).
-   **Integration:**
    -   Frontend handles the UI and temporary storage of payment details.
    -   Role changes are not client-authoritative. Premium roles must be granted by an admin or trusted server-side payment verification flow.

## Workflow

### 1. Plan Selection
**File:** `src/pages/Subscription/SubscriptionTierPage.jsx`
**Route:** `/subscribe`

-   Displays available plans (Basic vs. Premium) with features and pricing.
-   **Basic:** Free, default for new users.
-   **Premium:** ₱150/month. Adds background recording, cloud sync, etc.
-   **Action:** Selecting "Premium" saves the choice to `localStorage` (`selectedTier`) and redirects to `/payment`.

### 2. Payment Method Selection
**File:** `src/pages/Subscription/PaymentMethodPage.jsx`
**Route:** `/payment`

-   Users choose between:
    -   **Card:** Visa/Mastercard/Amex.
    -   **E-Wallet:** GCash/PayMaya.
-   **Components:**
    -   `CardPaymentForm`: Validates card number (Luhn algorithm logic simplified), expiry, CVV.
    -   `EWalletPaymentForm`: Validates 10-digit PH mobile number.
-   **Action:** Valid forms save details to `localStorage` (`paymentDetails`, `paymentMethod`) and redirect to `/checkout`.

### 3. Checkout & Confirmation
**File:** `src/pages/Subscription/CheckoutPage.jsx`
**Route:** `/checkout`

-   **Review:** Displays selected plan, price, and payment method summary.
-   **Confirmation:**
    -   Clicking "Confirm and Pay" simulates a payment process (1.5s delay).
    -   The checkout page records a non-authoritative `pendingSubscriptionTier` locally for UX only.
    -   A confirmation modal explains that Premium access activates after server-side verification.
-   **Error Handling:** Invalid or missing temporary checkout state redirects the user back to the appropriate setup step.

## Data Persistence

-   **Temporary:** `localStorage` is used to persist `selectedTier` and `paymentDetails` between steps in the funnel. These are cleared upon checkout confirmation; `pendingSubscriptionTier` may remain temporarily for non-authoritative UX messaging.
-   **Permanent:** The user's role in the database is the source of truth for their subscription status.