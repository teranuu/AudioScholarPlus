export const emitVisibleWarningReceipts = (entry, warnings, sentWarningIds, sendReceipt) => {
  if (!entry?.isIntersecting || entry.intersectionRatio < 0.5) return false;
  (warnings || []).forEach((warning) => {
    if (warning.warningId && !sentWarningIds.has(warning.warningId)) {
      sentWarningIds.add(warning.warningId);
      sendReceipt(warning.warningId, 'INDICATOR_VISIBLE');
    }
  });
  return true;
};

export const emitExpandedWarningReceipts = (warnings, sentWarningIds, sendReceipt) => {
  (warnings || []).forEach((warning) => {
    if (warning.warningId && !sentWarningIds.has(warning.warningId)) {
      sentWarningIds.add(warning.warningId);
      sendReceipt(warning.warningId, 'DETAILS_EXPANDED');
    }
  });
};
