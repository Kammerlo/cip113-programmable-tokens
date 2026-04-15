"use client";

import { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import { useWallet } from '@meshsdk/react';
import { resolveTxHash } from '@meshsdk/core';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { CopyButton } from '@/components/ui/copy-button';
import { useToast } from '@/components/ui/use-toast';
import { useProtocolVersion } from '@/contexts/protocol-version-context';
import { registerToken, stringToHex } from '@/lib/api';
import { getPaymentKeyHash } from '@/lib/utils/address';
import { getExplorerTxUrl } from '@/lib/utils/format';
import type { KycRegisterRequest } from '@/types/api';
import type { StepComponentProps, TokenDetailsData } from '@/types/registration';

type BuildStatus =
  | 'idle'
  | 'building'
  | 'preview'
  | 'signing'
  | 'submitting'
  | 'success'
  | 'error';

interface KycBuildResult {
  tokenPolicyId: string;
  regTxHash: string;
  globalStatePolicyId: string;
}

export function KycBuildSignSubmitStep({
  onComplete,
  onError,
  onBack,
  isProcessing,
  setProcessing,
  wizardState,
}: StepComponentProps<Record<string, unknown>, KycBuildResult>) {
  const { connected, wallet } = useWallet();
  const { toast: showToast } = useToast();
  const { selectedVersion } = useProtocolVersion();

  const [status, setStatus] = useState<BuildStatus>('idle');
  const [errorMessage, setErrorMessage] = useState('');

  const [unsignedCbor, setUnsignedCbor] = useState('');
  const [tokenPolicyId, setTokenPolicyId] = useState('');
  const [derivedTxHash, setDerivedTxHash] = useState('');
  const [regTxHash, setRegTxHash] = useState('');

  const showToastRef = useRef(showToast);
  useEffect(() => { showToastRef.current = showToast; }, [showToast]);

  const tokenDetails = useMemo(() => {
    const detailsState = wizardState.stepStates['token-details'];
    return (detailsState?.data || {}) as Partial<TokenDetailsData>;
  }, [wizardState.stepStates]);

  const globalStatePolicyId = useMemo(() => {
    const kycState = wizardState.stepStates['kyc-config'];
    return (kycState?.data as { globalStatePolicyId?: string })?.globalStatePolicyId || '';
  }, [wizardState.stepStates]);

  // ---- BUILD ----
  const handleBuild = useCallback(async () => {
    if (!connected || !wallet) {
      onError('Wallet not connected');
      return;
    }
    if (!tokenDetails.assetName || !tokenDetails.quantity) {
      onError('Token details missing');
      return;
    }
    if (!globalStatePolicyId) {
      onError('Global State Policy ID is required');
      return;
    }

    try {
      setProcessing(true);
      setErrorMessage('');
      setStatus('building');

      showToastRef.current({
        title: 'Building Transaction',
        description: 'Building KYC token registration...',
        variant: 'default',
      });

      const addresses = await wallet.getUsedAddresses();
      if (!addresses?.[0]) throw new Error('No wallet address found');
      const adminAddress = addresses[0];

      const adminPubKeyHash = getPaymentKeyHash(adminAddress);
      const regRequest: KycRegisterRequest = {
        substandardId: 'kyc',
        feePayerAddress: adminAddress,
        assetName: stringToHex(tokenDetails.assetName),
        quantity: tokenDetails.quantity,
        recipientAddress: tokenDetails.recipientAddress || '',
        adminPubKeyHash,
        globalStatePolicyId,
      };

      const regResponse = await registerToken(regRequest, selectedVersion?.txHash);
      setTokenPolicyId(regResponse.policyId);
      setUnsignedCbor(regResponse.unsignedCborTx);
      setDerivedTxHash(resolveTxHash(regResponse.unsignedCborTx));

      setStatus('preview');
      showToastRef.current({
        title: 'Transaction Built',
        description: 'Review and sign the registration transaction',
        variant: 'success',
      });
    } catch (error) {
      setStatus('error');
      const message = error instanceof Error ? error.message : 'Failed to build transaction';
      setErrorMessage(message);
      showToastRef.current({
        title: 'Build Failed',
        description: message,
        variant: 'error',
      });
      onError(message);
    } finally {
      setProcessing(false);
    }
  }, [connected, wallet, tokenDetails, globalStatePolicyId, selectedVersion, onError, setProcessing]);

  // ---- SIGN & SUBMIT ----
  const handleSignAndSubmit = useCallback(async () => {
    if (!connected || !wallet) {
      onError('Wallet not connected');
      return;
    }

    try {
      setProcessing(true);
      setErrorMessage('');
      setStatus('signing');

      showToastRef.current({
        title: 'Sign Transaction',
        description: 'Please sign the transaction in your wallet',
        variant: 'default',
      });

      const signedTx = await wallet.signTx(unsignedCbor, true);

      setStatus('submitting');
      showToastRef.current({
        title: 'Submitting',
        description: 'Submitting registration transaction...',
        variant: 'default',
      });

      const hash = await wallet.submitTx(signedTx);
      setRegTxHash(hash);

      setStatus('success');
      showToastRef.current({
        title: 'Registration Complete!',
        description: 'KYC token registered successfully',
        variant: 'success',
      });

      onComplete({
        stepId: 'kyc-build-sign',
        data: {
          tokenPolicyId,
          regTxHash: hash,
          globalStatePolicyId,
        },
        txHash: hash,
        completedAt: Date.now(),
      });
    } catch (error) {
      setStatus('error');
      const message = error instanceof Error ? error.message : 'Failed to sign or submit';
      setErrorMessage(message);

      if (message.toLowerCase().includes('user declined') ||
          message.toLowerCase().includes('user rejected')) {
        showToastRef.current({
          title: 'Transaction Cancelled',
          description: 'You cancelled the transaction',
          variant: 'default',
        });
      } else {
        showToastRef.current({
          title: 'Submission Failed',
          description: message,
          variant: 'error',
        });
        onError(message);
      }
    } finally {
      setProcessing(false);
    }
  }, [connected, wallet, unsignedCbor, tokenPolicyId, globalStatePolicyId, onComplete, onError, setProcessing]);

  const handleFullRetry = useCallback(() => {
    setStatus('idle');
    setErrorMessage('');
    setUnsignedCbor('');
    setTokenPolicyId('');
    setDerivedTxHash('');
    setRegTxHash('');
  }, []);

  const ExplorerLink = ({ txHash: hash }: { txHash: string }) => (
    <a
      href={getExplorerTxUrl(hash)}
      target="_blank"
      rel="noopener noreferrer"
      className="text-dark-400 hover:text-primary-400 transition-colors"
      title="View on cexplorer"
    >
      <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
      </svg>
    </a>
  );

  const isActive = status === 'building' || status === 'signing' || status === 'submitting';

  const getStatusMessage = () => {
    switch (status) {
      case 'building': return 'Building registration transaction...';
      case 'preview': return 'Review the transaction before signing';
      case 'signing': return 'Waiting for wallet signature...';
      case 'submitting': return 'Submitting registration...';
      case 'success': return 'Registration complete!';
      case 'error': return errorMessage || 'Operation failed';
      default: return 'Ready to build and register';
    }
  };

  return (
    <div className="space-y-6">
      <div>
        <h3 className="text-lg font-semibold text-white mb-2">
          {status === 'preview' ? 'Review & Sign' : 'Build & Register'}
        </h3>
        <p className="text-dark-300 text-sm">
          {status === 'idle'
            ? 'Build the registration transaction, sign it, and submit.'
            : status === 'preview'
            ? 'Review the details below, then sign the transaction.'
            : 'Processing your registration...'}
        </p>
      </div>

      {/* Idle state */}
      {status === 'idle' && (
        <>
          <Card className="p-4 space-y-3">
            <h4 className="font-medium text-white">Registration Summary</h4>
            <div className="grid grid-cols-2 gap-3 text-sm">
              <div>
                <span className="text-dark-400">Token Name</span>
                <p className="text-white font-medium">{tokenDetails.assetName || '-'}</p>
              </div>
              <div>
                <span className="text-dark-400">Initial Supply</span>
                <p className="text-white font-medium">
                  {tokenDetails.quantity ? BigInt(tokenDetails.quantity).toLocaleString() : '-'}
                </p>
              </div>
              <div>
                <span className="text-dark-400">Substandard</span>
                <p className="text-white font-medium">KYC</p>
              </div>
              <div className="col-span-2">
                <span className="text-dark-400">Global State Policy ID</span>
                <p className="text-white font-medium text-sm font-mono break-all">{globalStatePolicyId}</p>
              </div>
              {tokenDetails.recipientAddress && (
                <div className="col-span-2">
                  <span className="text-dark-400">Recipient</span>
                  <p className="text-white font-medium text-sm truncate">
                    {tokenDetails.recipientAddress}
                  </p>
                </div>
              )}
            </div>
          </Card>

          <Card className="p-4 bg-blue-500/10 border-blue-500/30">
            <div className="flex items-start gap-3">
              <svg className="w-5 h-5 text-blue-400 flex-shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <div>
                <p className="text-blue-300 font-medium text-sm">KYC Token Registration</p>
                <p className="text-blue-200/70 text-sm mt-1">
                  This will register a programmable token that requires KYC attestation for transfers.
                  Transfers must include a valid signature from a trusted entity in the Global State.
                </p>
              </div>
            </div>
          </Card>
        </>
      )}

      {/* Active spinner */}
      {isActive && (
        <Card className="p-6 text-center space-y-4">
          <div className="flex justify-center">
            <div className="w-12 h-12 border-4 border-primary-500 border-t-transparent rounded-full animate-spin" />
          </div>
          <p className="text-dark-300 font-medium">{getStatusMessage()}</p>
        </Card>
      )}

      {/* Preview */}
      {status === 'preview' && (
        <>
          <Card className="p-4 space-y-3">
            <h4 className="font-medium text-white">Token Details</h4>
            <div className="grid grid-cols-2 gap-3 text-sm">
              <div>
                <span className="text-dark-400">Token Name</span>
                <p className="text-white font-medium">{tokenDetails.assetName}</p>
              </div>
              <div>
                <span className="text-dark-400">Initial Supply</span>
                <p className="text-white font-medium">
                  {BigInt(tokenDetails.quantity || '0').toLocaleString()}
                </p>
              </div>
            </div>
          </Card>

          <Card className="p-4 space-y-2">
            <div className="flex items-center justify-between">
              <h4 className="font-medium text-white">Token Policy ID</h4>
              <CopyButton value={tokenPolicyId} />
            </div>
            <p className="text-sm text-primary-400 font-mono break-all">{tokenPolicyId}</p>
          </Card>

          <Card className="p-4 space-y-2">
            <div className="flex items-center justify-between">
              <h4 className="font-medium text-white">Global State Policy ID</h4>
              <CopyButton value={globalStatePolicyId} />
            </div>
            <p className="text-sm text-cyan-400 font-mono break-all">{globalStatePolicyId}</p>
          </Card>

          {derivedTxHash && (
            <Card className="p-4 space-y-2">
              <div className="flex items-center justify-between">
                <h4 className="font-medium text-white">Registration Tx Hash</h4>
                <div className="flex items-center gap-1.5">
                  <CopyButton value={derivedTxHash} />
                  <ExplorerLink txHash={derivedTxHash} />
                </div>
              </div>
              <p className="text-sm text-primary-400 font-mono break-all">{derivedTxHash}</p>
            </Card>
          )}
        </>
      )}

      {/* Success */}
      {status === 'success' && (
        <Card className="p-6 text-center space-y-4">
          <div className="flex justify-center">
            <div className="w-12 h-12 rounded-full bg-green-500/20 flex items-center justify-center">
              <svg className="w-6 h-6 text-green-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
            </div>
          </div>
          <p className="text-green-400 font-medium">Registration Complete!</p>
          <div className="mt-4 space-y-3 text-left">
            <div className="p-3 bg-dark-800 rounded">
              <div className="flex items-center justify-between mb-1">
                <span className="text-xs text-dark-400">Token Policy ID</span>
                <CopyButton value={tokenPolicyId} />
              </div>
              <p className="text-sm text-primary-400 font-mono break-all">{tokenPolicyId}</p>
            </div>
            <div className="p-3 bg-dark-800 rounded">
              <div className="flex items-center justify-between mb-1">
                <span className="text-xs text-dark-400">Global State Policy ID</span>
                <CopyButton value={globalStatePolicyId} />
              </div>
              <p className="text-sm text-cyan-400 font-mono break-all">{globalStatePolicyId}</p>
            </div>
            {regTxHash && (
              <div className="p-3 bg-dark-800 rounded">
                <div className="flex items-center justify-between mb-1">
                  <span className="text-xs text-dark-400">Registration Tx Hash</span>
                  <div className="flex items-center gap-1.5">
                    <CopyButton value={regTxHash} />
                    <ExplorerLink txHash={regTxHash} />
                  </div>
                </div>
                <p className="text-sm text-primary-400 font-mono break-all">{regTxHash}</p>
              </div>
            )}
          </div>
        </Card>
      )}

      {/* Error */}
      {status === 'error' && (
        <Card className="p-4 bg-red-500/10 border-red-500/30">
          <div className="flex items-start gap-3">
            <svg className="w-5 h-5 text-red-400 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            <div>
              <p className="text-red-400 font-medium">Operation Failed</p>
              <p className="text-red-300 text-sm mt-1">{errorMessage}</p>
            </div>
          </div>
        </Card>
      )}

      {/* Actions */}
      <div className="flex gap-3">
        {onBack && status !== 'success' && !isActive && (
          <Button variant="outline" onClick={onBack} disabled={isProcessing}>
            Back
          </Button>
        )}

        {status === 'idle' && (
          <Button
            variant="primary"
            className="flex-1"
            onClick={handleBuild}
            disabled={isProcessing || !connected}
          >
            Build Registration
          </Button>
        )}

        {status === 'preview' && (
          <Button
            variant="primary"
            className="flex-1"
            onClick={handleSignAndSubmit}
            disabled={isProcessing || !connected}
          >
            Sign & Submit
          </Button>
        )}

        {status === 'error' && (
          <Button
            variant="primary"
            className="flex-1"
            onClick={handleFullRetry}
            disabled={isProcessing}
          >
            Rebuild From Scratch
          </Button>
        )}
      </div>

      {!connected && (
        <p className="text-sm text-center text-dark-400">
          Connect your wallet to continue
        </p>
      )}
    </div>
  );
}
