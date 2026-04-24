"use client";

import { useState, useRef } from "react";
import { useWallet } from "@/hooks/use-wallet";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { TxBuilderToggle, type TransactionBuilder } from "@/components/ui/tx-builder-toggle";
import {
  Coins,
  CheckCircle,
  ExternalLink,
  Shield,
} from "lucide-react";
import { AdminTokenSelector } from "./AdminTokenSelector";
import { AdminTokenInfo } from "@/lib/api/admin";
import { decodeAssetNameDisplay } from "@/lib/utils/cip68";
import { mintToken } from "@/lib/api";
import { MintTokenRequest, Cip170AttestationData } from "@/types/api";
import { useProtocolVersion } from "@/contexts/protocol-version-context";
import { useCIP113 } from "@/contexts/cip113-context";
import { useToast } from "@/components/ui/use-toast";
import { getExplorerTxUrl } from "@/lib/utils";
import {
  requestAttestation,
} from "@/lib/api/keri";

interface MintSectionProps {
  tokens: AdminTokenInfo[];
  feePayerAddress: string;
}

type MintStep = "form" | "attestation" | "signing" | "success";

function getSessionId(): string {
  if (typeof sessionStorage === "undefined") return crypto.randomUUID();
  let id = sessionStorage.getItem("mint-keri-session-id");
  if (!id) {
    id = crypto.randomUUID();
    sessionStorage.setItem("mint-keri-session-id", id);
  }
  return id;
}

export function MintSection({ tokens, feePayerAddress }: MintSectionProps) {
  const { wallet } = useWallet();
  const { toast: showToast } = useToast();
  const { selectedVersion } = useProtocolVersion();
  const { getProtocol, ensureSubstandard, available: sdkAvailable } = useCIP113();
  const [txBuilder, setTxBuilder] = useState<TransactionBuilder>(sdkAvailable ? "sdk" : "backend");

  // Filter tokens where user has ISSUER_ADMIN role or is a dummy token
  const mintableTokens = tokens.filter(
    (t) => t.roles.includes("ISSUER_ADMIN") || t.substandardId === "dummy"
  );

  // Form state
  const [selectedToken, setSelectedToken] = useState<AdminTokenInfo | null>(
    null
  );
  const [quantity, setQuantity] = useState("");
  const [recipientAddress, setRecipientAddress] = useState(feePayerAddress);
  const [enableAttestation, setEnableAttestation] = useState(false);

  // Flow state
  const [step, setStep] = useState<MintStep>("form");
  const [isBuilding, setIsBuilding] = useState(false);
  const [isSigning, setIsSigning] = useState(false);
  const [txHash, setTxHash] = useState<string | null>(null);
  const [attestError, setAttestError] = useState<string | null>(null);

  // CIP-170 state
  const [attestationData, setAttestationData] =
    useState<Cip170AttestationData | null>(null);
  const sessionIdRef = useRef(getSessionId());

  const [errors, setErrors] = useState({
    token: "",
    quantity: "",
    recipientAddress: "",
  });

  const isKycToken = selectedToken?.substandardId === "kyc";

  const validateForm = (): boolean => {
    const newErrors = {
      token: "",
      quantity: "",
      recipientAddress: "",
    };

    if (!selectedToken) {
      newErrors.token = "Please select a token to mint";
    }

    if (!quantity.trim()) {
      newErrors.quantity = "Quantity is required";
    } else if (!/^\d+$/.test(quantity)) {
      newErrors.quantity = "Quantity must be a positive number";
    } else if (BigInt(quantity) <= 0) {
      newErrors.quantity = "Quantity must be greater than 0";
    }

    if (!recipientAddress.trim()) {
      newErrors.recipientAddress = "Recipient address is required";
    } else if (!recipientAddress.startsWith("addr")) {
      newErrors.recipientAddress = "Invalid Cardano address format";
    }

    setErrors(newErrors);
    return !Object.values(newErrors).some((error) => error !== "");
  };

  // ── Form submission ──────────────────────────────────────────────────────

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validateForm() || !selectedToken) return;

    if (enableAttestation && isKycToken) {
      // Go to attestation step — request digest anchoring then mint
      setStep("attestation");
    } else {
      // Direct mint (no attestation)
      await buildAndSignMint(null);
    }
  };

  // ── Direct mint flow ─────────────────────────────────────────────────────

  const buildAndSignMint = async (
    attestation: Cip170AttestationData | null
  ) => {
    if (!selectedToken) return;

    try {
      setIsBuilding(true);
      setStep("signing");

      let unsignedCborTx: string;

      if (txBuilder === "sdk") {
        const substandardId = await ensureSubstandard(selectedToken.policyId, selectedToken.assetName);
        const protocol = await getProtocol();
        const result = await protocol.mint({
          feePayerAddress,
          tokenPolicyId: selectedToken.policyId,
          assetName: selectedToken.assetName,
          quantity: BigInt(quantity),
          recipientAddress: recipientAddress.trim(),
          substandardId,
        });
        unsignedCborTx = result.cbor;
      } else {
        const request: MintTokenRequest = {
          feePayerAddress,
          tokenPolicyId: selectedToken.policyId,
          assetName: selectedToken.assetName,
          quantity,
          recipientAddress: recipientAddress.trim(),
          ...(attestation ? { attestation } : {}),
        };
        unsignedCborTx = await mintToken(request, selectedVersion?.txHash);
      }

      setIsBuilding(false);
      setIsSigning(true);

      const signedTx = await wallet.signTx(unsignedCborTx);
      const submittedTxHash = await wallet.submitTx(signedTx);

      setTxHash(submittedTxHash);
      setStep("success");

      showToast({
        title: "Mint Successful",
        description: `Minted ${quantity} ${decodeAssetNameDisplay(selectedToken.assetName)} tokens`,
        variant: "success",
      });
    } catch (error) {
      console.error("Mint error:", error);

      let errorMessage = "Failed to mint tokens";
      if (error instanceof Error) {
        if (error.message.includes("User declined")) {
          errorMessage = "Transaction was cancelled";
        } else {
          errorMessage = error.message;
        }
      }

      showToast({
        title: "Mint Failed",
        description: errorMessage,
        variant: "error",
      });

      setStep("form");
    } finally {
      setIsBuilding(false);
      setIsSigning(false);
    }
  };

  // ── Attestation Anchoring ────────────────────────────────────────────────

  const handleAttestation = async () => {
    if (!selectedToken) return;

    try {
      setIsBuilding(true);
      setAttestError(null);

      const unit = selectedToken.policyId + selectedToken.assetName;

      const attestData = await requestAttestation(
        sessionIdRef.current,
        unit,
        quantity
      );

      setAttestationData(attestData);

      showToast({
        title: "Attestation Anchored",
        description: "Digest anchored in KEL. Building mint transaction...",
        variant: "success",
      });

      // Proceed to build and sign mint tx with attestation
      await buildAndSignMint(attestData);
    } catch (error) {
      console.error("Attestation error:", error);
      let errorMessage = "Failed to anchor attestation";
      if (error instanceof Error) {
        if (error.message.includes("408")) {
          errorMessage =
            "Wallet did not respond to anchor request in time.";
        } else if (error.message.includes("409")) {
          errorMessage = "Attestation request was cancelled.";
        } else {
          errorMessage = error.message;
        }
      }
      setAttestError(errorMessage);
    } finally {
      setIsBuilding(false);
    }
  };

  // ── Reset ────────────────────────────────────────────────────────────────

  const handleReset = () => {
    setStep("form");
    setQuantity("");
    setRecipientAddress(feePayerAddress);
    setTxHash(null);
    setEnableAttestation(false);
    setAttestationData(null);
    setAttestError(null);
    setErrors({ token: "", quantity: "", recipientAddress: "" });
    sessionStorage.removeItem("mint-keri-session-id");
    sessionIdRef.current = getSessionId();
  };

  // ── Renders ──────────────────────────────────────────────────────────────

  if (mintableTokens.length === 0) {
    return (
      <div className="flex flex-col items-center py-12 px-6">
        <Coins className="h-16 w-16 text-dark-600 mb-4" />
        <h3 className="text-lg font-semibold text-white mb-2">
          No Minting Access
        </h3>
        <p className="text-sm text-dark-400 text-center">
          You don&apos;t have issuer admin permissions for any tokens.
        </p>
      </div>
    );
  }

  // ── Success step ─────────────────────────────────────────────────────────

  if (step === "success" && txHash) {
    return (
      <div className="flex flex-col items-center py-8">
        <div className="w-16 h-16 rounded-full bg-green-500/10 flex items-center justify-center mb-4">
          <CheckCircle className="h-8 w-8 text-green-500" />
        </div>
        <h3 className="text-lg font-semibold text-white mb-2">
          Mint Complete!
        </h3>
        <p className="text-sm text-dark-400 text-center mb-4">
          Successfully minted {quantity}{" "}
          {selectedToken ? decodeAssetNameDisplay(selectedToken.assetName) : ""}{" "}
          tokens
        </p>

        <div className="w-full px-4 py-3 bg-dark-900 rounded-lg mb-4">
          <p className="text-xs text-dark-400 mb-1">Mint Transaction Hash</p>
          <p className="text-xs text-primary-400 font-mono break-all">
            {txHash}
          </p>
        </div>

        {attestationData && (
          <div className="w-full px-4 py-3 bg-dark-900 rounded-lg mb-4">
            <div className="flex items-center gap-2 mb-2">
              <Shield className="h-4 w-4 text-green-400" />
              <p className="text-xs text-green-400 font-medium">
                CIP-170 Attested
              </p>
            </div>
            <p className="text-xs text-dark-400">
              Signer: {attestationData.signerAid}
            </p>
            <p className="text-xs text-dark-400">
              Digest: {attestationData.digest}
            </p>
          </div>
        )}

        <div className="flex gap-3 w-full">
          <a
            href={getExplorerTxUrl(txHash)}
            target="_blank"
            rel="noopener noreferrer"
            className="flex-1"
          >
            <Button variant="ghost" className="w-full">
              <ExternalLink className="h-4 w-4 mr-2" />
              View on Explorer
            </Button>
          </a>
          <Button variant="primary" className="flex-1" onClick={handleReset}>
            Mint More
          </Button>
        </div>
      </div>
    );
  }

  // ── Signing step ─────────────────────────────────────────────────────────

  if (step === "signing") {
    return (
      <div className="flex flex-col items-center py-12">
        <div className="h-12 w-12 border-4 border-primary-500 border-t-transparent rounded-full animate-spin mb-4" />
        <p className="text-white font-medium">
          {isSigning
            ? "Waiting for signature..."
            : "Building transaction..."}
        </p>
        <p className="text-sm text-dark-400 mt-2">
          Please confirm the transaction in your wallet
        </p>
      </div>
    );
  }

  // ── Attestation step ─────────────────────────────────────────────────────

  if (step === "attestation") {
    return (
      <div className="space-y-6">
        <h3 className="text-lg font-semibold text-white">
          CIP-170 Attestation
        </h3>

        <p className="text-sm text-dark-400">
          The digest of your mint (token unit + quantity) will be sent to your
          Veridian wallet for anchoring. Please approve the interact event in
          your wallet when prompted.
        </p>

        {attestError && (
          <div className="px-4 py-3 bg-red-500/10 border border-red-500/30 rounded-lg">
            <p className="text-sm text-red-400">{attestError}</p>
          </div>
        )}

        <div className="px-4 py-3 bg-dark-900 rounded-lg space-y-1">
          <p className="text-xs text-dark-400">Token</p>
          <p className="text-sm text-white">
            {selectedToken?.assetNameDisplay}
          </p>
          <p className="text-xs text-dark-400 mt-2">Quantity</p>
          <p className="text-sm text-white">{quantity}</p>
        </div>

        <div className="flex gap-3">
          <Button
            variant="outline"
            onClick={() => {
              setStep("form");
              setAttestError(null);
            }}
          >
            Back
          </Button>
          <Button
            variant="primary"
            className="flex-1"
            onClick={handleAttestation}
            isLoading={isBuilding}
            disabled={isBuilding}
          >
            {isBuilding
              ? "Waiting for wallet..."
              : "Anchor Digest & Mint with Attestation"}
          </Button>
        </div>
      </div>
    );
  }

  // ── Form step ────────────────────────────────────────────────────────────

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      <TxBuilderToggle value={txBuilder} onChange={setTxBuilder} sdkAvailable={sdkAvailable} />

      {/* Token Selector */}
      <div>
        <AdminTokenSelector
          tokens={mintableTokens}
          selectedToken={selectedToken}
          onSelect={(token) => {
            setSelectedToken(token);
            setEnableAttestation(false);
            setErrors((prev) => ({ ...prev, token: "" }));
          }}
          disabled={isBuilding}
          filterByRole="ISSUER_ADMIN"
        />
        {errors.token && (
          <p className="mt-2 text-sm text-red-400">{errors.token}</p>
        )}
      </div>

      {/* Quantity */}
      <Input
        label="Quantity"
        type="number"
        value={quantity}
        onChange={(e) => {
          setQuantity(e.target.value);
          setErrors((prev) => ({ ...prev, quantity: "" }));
        }}
        placeholder="Enter amount to mint"
        disabled={isBuilding || !selectedToken}
        error={errors.quantity}
        helperText="Number of tokens to mint"
      />

      {/* Recipient Address */}
      <Input
        label="Recipient Address"
        value={recipientAddress}
        onChange={(e) => {
          setRecipientAddress(e.target.value);
          setErrors((prev) => ({ ...prev, recipientAddress: "" }));
        }}
        placeholder="addr1..."
        disabled={isBuilding || !selectedToken}
        error={errors.recipientAddress}
        helperText="Address to receive the minted tokens"
      />

      {/* CIP-170 Attestation Toggle (only for KYC tokens) */}
      {isKycToken && (
        <div className="px-4 py-3 bg-dark-900 rounded-lg">
          <label className="flex items-center gap-3 cursor-pointer">
            <input
              type="checkbox"
              checked={enableAttestation}
              onChange={(e) => setEnableAttestation(e.target.checked)}
              disabled={isBuilding}
              className="w-4 h-4 rounded border-dark-600 bg-dark-800 text-primary-500 focus:ring-primary-500"
            />
            <div>
              <p className="text-sm text-white font-medium">
                Enable CIP-170 Attestation
              </p>
              <p className="text-xs text-dark-400">
                Attest this mint with your Veridian wallet (anchors digest in
                KEL and attaches ATTEST metadata to the mint transaction)
              </p>
            </div>
          </label>
        </div>
      )}

      {/* Submit Button */}
      <Button
        type="submit"
        variant="primary"
        className="w-full"
        isLoading={isBuilding}
        disabled={isBuilding || !selectedToken}
      >
        {isBuilding
          ? "Building Transaction..."
          : enableAttestation
            ? "Start Attestation & Mint"
            : "Mint Tokens"}
      </Button>
    </form>
  );
}
