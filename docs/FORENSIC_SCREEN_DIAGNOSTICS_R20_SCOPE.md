# R20 scope isolation

R20 is injected from the earliest native-tap document-start script only when the live route URL contains `gemini-3.8-live`. Translation and transcription routes do not execute the R20 body. The branch is intentionally temporary and should be reverted after the screen-description transport failure is identified.
