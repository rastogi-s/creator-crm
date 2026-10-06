# Code signing for Windows

Unsigned installers make Windows show **"Windows protected your PC"** and **Unknown publisher** the first time
they're opened. Signing puts a verified name on `Creator CRM.exe` and the `.msi`, and the release build does it
automatically once the steps below are done. Until then, releases keep building unsigned, as before.

The release build uses [Azure Artifact Signing](https://learn.microsoft.com/en-us/azure/artifact-signing/quickstart)
(formerly Trusted Signing). It costs **$9.99 a month** (Basic: 5,000 signatures, which is far more than needed),
needs no hardware token, and is open to individuals in the **USA and Canada** and to organisations in the USA,
Canada, the EU and the UK. Microsoft checks your identity once, with a government ID.

## 1. Create the signing account (Azure portal, about 30 minutes plus Microsoft's identity check)

1. Sign in at [portal.azure.com](https://portal.azure.com) (create a free Azure account if you don't have one; it
   needs a credit card for the monthly fee).
2. Search for **Artifact Signing accounts** → **Create**. Pick a resource group (create one, e.g. `creator-crm`),
   a name (e.g. `creatorcrm`), a region (e.g. *East US*) and the **Basic** tier.
3. Give yourself the role that lets you request an identity check: on the new account, **Access control (IAM)** →
   **Add role assignment** → **Artifact Signing Identity Verifier** → your own user.
4. On the account, **Identity validations** → **New identity** → **Public** → **Individual** (or Organization),
   and follow the steps. Wait for the status to say **Completed** (often within a day, sometimes longer).
5. On the account, **Certificate profiles** → **Create** → **Public Trust**, pick the validated identity, and name
   it (e.g. `creator-crm`).
6. Note the account's **Account URI** from its Overview page (e.g. `https://eus.codesigning.azure.net/`).

## 2. Let GitHub sign in to Azure without a password

1. In the portal, **Microsoft Entra ID** → **App registrations** → **New registration**. Name it
   `creator-crm-github` and register it. From its Overview, note the **Application (client) ID** and the
   **Directory (tenant) ID**.
2. On that app registration: **Certificates & secrets** → **Federated credentials** → **Add credential** →
   **GitHub Actions deploying Azure resources**. Organization `rastogi-s`, repository `creator-crm`, entity type
   **Branch**, branch `main`. Save.
3. Back on the signing account: **Access control (IAM)** → **Add role assignment** →
   **Artifact Signing Certificate Profile Signer** → assign it to `creator-crm-github`.
4. Note your **Subscription ID** (search *Subscriptions* in the portal).

## 3. Tell the repository

On GitHub: the repository → **Settings** → **Secrets and variables** → **Actions**.

Under **Variables**, add:

| Name | Value |
|---|---|
| `AZURE_SIGNING_ENDPOINT` | the Account URI, e.g. `https://eus.codesigning.azure.net/` |
| `AZURE_SIGNING_ACCOUNT` | the signing account's name, e.g. `creatorcrm` |
| `AZURE_SIGNING_PROFILE` | the certificate profile's name, e.g. `creator-crm` |

Under **Secrets**, add `AZURE_CLIENT_ID`, `AZURE_TENANT_ID` and `AZURE_SUBSCRIPTION_ID` with the values from
step 2. None of these is a password: GitHub proves it's this repository's `main` branch, and Azure only lets
that sign.

## 4. Check it

The next merge to `main` releases a signed build. In the run's **installers (windows-latest)** job, the step
**Check the signatures** prints the signer's name for `Creator CRM.exe` and the `.msi`. On a Windows computer,
right-click the downloaded `.msi` → **Properties** → **Digital Signatures** shows the same name.

Notes:
- Only the automatic release from `main` is signed. A version tag pushed by hand still builds, unsigned.
- Windows SmartScreen also weighs how many people have run a file. With Artifact Signing the publisher name shows
  straight away; the blue warning screen can still appear for the first few downloads of a brand-new publisher.
- To stop signing, delete the `AZURE_SIGNING_ENDPOINT` variable. The build goes back to unsigned installers.

## macOS

Mac installers stay unsigned: signing and notarising them needs an Apple Developer account ($99 a year). The
first time, macOS says the app "can't be opened"; go to **System Settings → Privacy & Security** and click
**Open Anyway**. After that, **Update now** replaces the app in place, with no warning, as long as it lives in
the Applications folder.
