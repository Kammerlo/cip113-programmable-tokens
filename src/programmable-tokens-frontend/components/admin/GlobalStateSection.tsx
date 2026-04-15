"use client";

import { useState } from "react";
import { useWallet } from "@meshsdk/react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Settings,
  PauseCircle,
  PlayCircle,
  UserCheck,
  Plus,
  Minus,
  CheckCircle,
  ExternalLink,
} from "lucide-react";
import { AdminTokenSelector } from "./AdminTokenSelector";
import { AdminTokenInfo } from "@/lib/api/admin";
import { getSigningEntityVkey } from "@/lib/api/keri";
import { useProtocolVersion } from "@/contexts/protocol-version-context";
import { useToast } from "@/components/ui/use-toast";
import { getExplorerTxUrl } from "@/lib/utils";
import { cn } from "@/lib/utils";
import type { GlobalStateAction } from "@/types/compliance";

interface GlobalStateSectionProps {
  tokens: AdminTokenInfo[];
  adminAddress: string;
}

type SectionStep = "form" | "signing" | "success";
type ActionType = GlobalStateAction | "ADD_TRUSTED_ENTITY" | "REMOVE_TRUSTED_ENTITY";

export function GlobalStateSection({
  tokens,
  adminAddress,
}: GlobalStateSectionProps) {
  const { wallet } = useWallet();
  const { toast: showToast } = useToast();
  const { selectedVersion } = useProtocolVersion();

  const manageableTokens = tokens.filter(
    (t) => t.roles.includes("ISSUER_ADMIN") && t.substandardId === "kyc"
  );

  const [selectedToken, setSelectedToken] = useState<AdminTokenInfo | null>(null);
  const [action, setAction] = useState<ActionType>("PAUSE_TRANSFERS");
  const [transfersPaused, setTransfersPaused] = useState(true);
  const [mintableAmount, setMintableAmount] = useState("");
  const [securityInfo, setSecurityInfo] = useState("");
  const [targetVkey, setTargetVkey] = useState("");
  const [step, setStep] = useState<SectionStep>("form");
  const [isBuilding, setIsBuilding] = useState(false);
  const [txHash, setTxHash] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!selectedToken) {
      showToast({ title: "Validation Error", description: "Please select a token", variant: "error" });
      return;
    }

    if (action === "UPDATE_MINTABLE_AMOUNT" && !mintableAmount.trim()) {
      showToast({ title: "Validation Error", description: "Mintable amount is required", variant: "error" });
      return;
    }

    if ((action === "ADD_TRUSTED_ENTITY" || action === "REMOVE_TRUSTED_ENTITY") &&
        !/^[0-9a-fA-F]{64}$/.test(targetVkey.trim())) {
      showToast({ title: "Validation Error", description: "Must be a 64-character hex Ed25519 verification key", variant: "error" });
      return;
    }

    try {
      setIsBuilding(true);

      let unsignedCborTx: string;

      if (action === "ADD_TRUSTED_ENTITY" || action === "REMOVE_TRUSTED_ENTITY") {
        const { addToWhitelist, removeFromWhitelist } = await import("@/lib/api/compliance");
        const request = {
          adminAddress,
          targetCredential: targetVkey.trim(),
          policyId: selectedToken.policyId,
        };
        const response = action === "ADD_TRUSTED_ENTITY"
          ? await addToWhitelist(request, selectedVersion?.txHash)
          : await removeFromWhitelist(request, selectedVersion?.txHash);
        unsignedCborTx = response.unsignedCborTx;
      } else {
        const { updateGlobalState } = await import("@/lib/api/compliance");
        const response = await updateGlobalState(
          {
            adminAddress,
            policyId: selectedToken.policyId,
            action: action as GlobalStateAction,
            transfersPaused: action === "PAUSE_TRANSFERS" ? transfersPaused : undefined,
            mintableAmount: action === "UPDATE_MINTABLE_AMOUNT" ? parseInt(mintableAmount, 10) : undefined,
            securityInfo: action === "MODIFY_SECURITY_INFO" ? securityInfo || undefined : undefined,
          },
          selectedVersion?.txHash
        );

        if (!response.isSuccessful || !response.unsignedCborTx) {
          throw new Error(response.error || "Failed to build transaction");
        }
        unsignedCborTx = response.unsignedCborTx;
      }

      setIsBuilding(false);
      setStep("signing");

      const signedTx = await wallet.signTx(unsignedCborTx, true);
      const submittedTxHash = await wallet.submitTx(signedTx);

      setTxHash(submittedTxHash);
      setStep("success");

      const labels: Record<ActionType, string> = {
        PAUSE_TRANSFERS: transfersPaused ? "Transfers Paused" : "Transfers Unpaused",
        UPDATE_MINTABLE_AMOUNT: "Mintable Amount Updated",
        MODIFY_SECURITY_INFO: "Security Info Updated",
        ADD_TRUSTED_ENTITY: "Trusted Entity Added",
        REMOVE_TRUSTED_ENTITY: "Trusted Entity Removed",
      };

      showToast({ title: labels[action], description: "Global state updated successfully", variant: "success" });
    } catch (error) {
      console.error("Global state update error:", error);
      const errorMessage = error instanceof Error
        ? (error.message.includes("User declined") ? "Transaction was cancelled" : error.message)
        : "Failed to update global state";
      showToast({ title: "Update Failed", description: errorMessage, variant: "error" });
      setStep("form");
    } finally {
      setIsBuilding(false);
    }
  };

  const handleReset = () => {
    setStep("form");
    setTxHash(null);
    setMintableAmount("");
    setSecurityInfo("");
    setTargetVkey("");
  };

  if (manageableTokens.length === 0) {
    return (
      <div className="flex flex-col items-center py-12 px-6">
        <Settings className="h-16 w-16 text-dark-600 mb-4" />
        <h3 className="text-lg font-semibold text-white mb-2">No KYC Token Management Access</h3>
        <p className="text-sm text-dark-400 text-center">
          You don&apos;t have issuer admin permissions for any KYC tokens.
        </p>
      </div>
    );
  }

  if (step === "success" && txHash) {
    return (
      <div className="flex flex-col items-center py-8">
        <div className="w-16 h-16 rounded-full bg-green-500/10 flex items-center justify-center mb-4">
          <CheckCircle className="h-8 w-8 text-green-500" />
        </div>
        <h3 className="text-lg font-semibold text-white mb-2">Global State Updated</h3>
        <p className="text-sm text-dark-400 text-center mb-4">
          The on-chain global state has been updated.
        </p>
        <div className="w-full px-4 py-3 bg-dark-900 rounded-lg mb-4">
          <p className="text-xs text-dark-400 mb-1">Transaction Hash</p>
          <p className="text-xs text-primary-400 font-mono break-all">{txHash}</p>
        </div>
        <div className="flex gap-3 w-full">
          <a href={getExplorerTxUrl(txHash)} target="_blank" rel="noopener noreferrer" className="flex-1">
            <Button variant="ghost" className="w-full">
              <ExternalLink className="h-4 w-4 mr-2" />
              View on Explorer
            </Button>
          </a>
          <Button variant="primary" className="flex-1" onClick={handleReset}>Update More</Button>
        </div>
      </div>
    );
  }

  if (step === "signing") {
    return (
      <div className="flex flex-col items-center py-12">
        <div className="h-12 w-12 border-4 border-primary-500 border-t-transparent rounded-full animate-spin mb-4" />
        <p className="text-white font-medium">Waiting for signature...</p>
        <p className="text-sm text-dark-400 mt-2">Please confirm the transaction in your wallet</p>
      </div>
    );
  }

  const actionButtons: { id: ActionType; label: string; icon: React.ReactNode }[] = [
    { id: "PAUSE_TRANSFERS", label: "Pause / Unpause", icon: <PauseCircle className="h-4 w-4" /> },
    { id: "UPDATE_MINTABLE_AMOUNT", label: "Mintable Amount", icon: <Settings className="h-4 w-4" /> },
    { id: "MODIFY_SECURITY_INFO", label: "Security Info", icon: <Settings className="h-4 w-4" /> },
    { id: "ADD_TRUSTED_ENTITY", label: "Add Entity", icon: <Plus className="h-4 w-4" /> },
    { id: "REMOVE_TRUSTED_ENTITY", label: "Remove Entity", icon: <Minus className="h-4 w-4" /> },
  ];

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      {/* Token Selector */}
      <div>
        <AdminTokenSelector
          tokens={manageableTokens}
          selectedToken={selectedToken}
          onSelect={setSelectedToken}
          disabled={isBuilding}
          filterByRole="ISSUER_ADMIN"
        />
      </div>

      {/* Action Selector */}
      <div>
        <label className="block text-sm font-medium text-white mb-2">Action</label>
        <div className="grid grid-cols-3 gap-2">
          {actionButtons.slice(0, 3).map(({ id, label, icon }) => (
            <button
              key={id}
              type="button"
              onClick={() => setAction(id)}
              disabled={isBuilding}
              className={cn(
                "flex flex-col items-center gap-1.5 px-3 py-3 rounded-lg border transition-colors text-xs",
                action === id
                  ? "bg-primary-500/10 border-primary-500 text-primary-400"
                  : "bg-dark-800 border-dark-700 text-dark-400 hover:border-dark-600"
              )}
            >
              {icon}
              {label}
            </button>
          ))}
        </div>

        {/* Trusted Entity row */}
        <div className="flex items-center gap-2 mt-2">
          <UserCheck className="h-3.5 w-3.5 text-dark-500 shrink-0" />
          <span className="text-xs text-dark-500">Trusted Entities:</span>
          <div className="flex gap-2 flex-1">
            {actionButtons.slice(3).map(({ id, label, icon }) => (
              <button
                key={id}
                type="button"
                onClick={() => setAction(id)}
                disabled={isBuilding}
                className={cn(
                  "flex-1 flex items-center justify-center gap-1.5 px-3 py-2.5 rounded-lg border transition-colors text-xs",
                  action === id
                    ? id === "ADD_TRUSTED_ENTITY"
                      ? "bg-green-500/10 border-green-500 text-green-400"
                      : "bg-red-500/10 border-red-500 text-red-400"
                    : "bg-dark-800 border-dark-700 text-dark-400 hover:border-dark-600"
                )}
              >
                {icon}
                {label}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Action-specific inputs */}
      {action === "PAUSE_TRANSFERS" && (
        <div>
          <label className="block text-sm font-medium text-white mb-2">Transfer Status</label>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => setTransfersPaused(true)}
              disabled={isBuilding}
              className={cn(
                "flex-1 flex items-center justify-center gap-2 px-4 py-3 rounded-lg border transition-colors",
                transfersPaused
                  ? "bg-red-500/10 border-red-500 text-red-400"
                  : "bg-dark-800 border-dark-700 text-dark-400 hover:border-dark-600"
              )}
            >
              <PauseCircle className="h-4 w-4" />
              Pause Transfers
            </button>
            <button
              type="button"
              onClick={() => setTransfersPaused(false)}
              disabled={isBuilding}
              className={cn(
                "flex-1 flex items-center justify-center gap-2 px-4 py-3 rounded-lg border transition-colors",
                !transfersPaused
                  ? "bg-green-500/10 border-green-500 text-green-400"
                  : "bg-dark-800 border-dark-700 text-dark-400 hover:border-dark-600"
              )}
            >
              <PlayCircle className="h-4 w-4" />
              Unpause Transfers
            </button>
          </div>
          <p className="mt-2 text-xs text-dark-400">
            When paused, no transfers of this token can be executed on-chain.
          </p>
        </div>
      )}

      {action === "UPDATE_MINTABLE_AMOUNT" && (
        <Input
          label="Mintable Amount"
          type="number"
          min="0"
          value={mintableAmount}
          onChange={(e) => setMintableAmount(e.target.value)}
          placeholder="Enter the new mintable amount"
          disabled={isBuilding || !selectedToken}
          helperText="Maximum number of tokens that can still be minted. Set to 0 to prevent further minting."
        />
      )}

      {action === "MODIFY_SECURITY_INFO" && (
        <Input
          label="Security Info (hex)"
          value={securityInfo}
          onChange={(e) => setSecurityInfo(e.target.value)}
          placeholder="Hex-encoded security/compliance metadata"
          disabled={isBuilding || !selectedToken}
          helperText="Arbitrary compliance metadata stored on-chain as hex bytes. Leave empty to clear."
        />
      )}

      {(action === "ADD_TRUSTED_ENTITY" || action === "REMOVE_TRUSTED_ENTITY") && (
        <div className="space-y-2">
          <Input
            label="Verification Key"
            value={targetVkey}
            onChange={(e) => setTargetVkey(e.target.value)}
            placeholder="64-character hex Ed25519 verification key"
            disabled={isBuilding || !selectedToken}
            helperText={
              action === "ADD_TRUSTED_ENTITY"
                ? "Ed25519 vkey of the entity to authorize for KYC attestations"
                : "Ed25519 vkey of the entity to remove from the trusted list"
            }
          />
          {action === "ADD_TRUSTED_ENTITY" && (
            <Button
              type="button"
              variant="outline"
              className="text-xs h-7 px-3"
              onClick={async () => {
                try {
                  const response = await getSigningEntityVkey();
                  setTargetVkey(response.vkeyHex);
                } catch (err) {
                  showToast({
                    title: "Error",
                    description: err instanceof Error ? err.message : "Failed to load signing entity key",
                    variant: "error",
                  });
                }
              }}
              disabled={isBuilding}
            >
              Load signing entity key
            </Button>
          )}
        </div>
      )}

      {/* Submit Button */}
      <Button
        type="submit"
        variant={action === "REMOVE_TRUSTED_ENTITY" ? "danger" : "primary"}
        className="w-full"
        isLoading={isBuilding}
        disabled={isBuilding || !selectedToken}
      >
        {isBuilding ? "Building Transaction..." :
          action === "PAUSE_TRANSFERS" ? (transfersPaused ? "Pause Transfers" : "Unpause Transfers") :
          action === "UPDATE_MINTABLE_AMOUNT" ? "Update Mintable Amount" :
          action === "MODIFY_SECURITY_INFO" ? "Update Security Info" :
          action === "ADD_TRUSTED_ENTITY" ? "Add Trusted Entity" :
          "Remove Trusted Entity"}
      </Button>
    </form>
  );
}
