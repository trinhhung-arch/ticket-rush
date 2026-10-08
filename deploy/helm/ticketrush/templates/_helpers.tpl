{{/* Labels shared by every object of one component. */}}
{{- define "ticketrush.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: ticketrush
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
helm.sh/chart: {{ .root.Chart.Name }}-{{ .root.Chart.Version }}
{{- end }}

{{- define "ticketrush.selector" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end }}

{{/* Name of the Secret holding every password and key. */}}
{{- define "ticketrush.secretName" -}}
{{- default (printf "%s-secrets" .Release.Name) .Values.secrets.existingSecret -}}
{{- end }}

{{/* env entry read from the shared Secret. */}}
{{- define "ticketrush.secretEnv" -}}
- name: {{ .env }}
  valueFrom:
    secretKeyRef:
      name: {{ include "ticketrush.secretName" .root }}
      key: {{ .key }}
{{- end }}

{{- /*
Pod-level security for every pod (INF-06, INF-08), so the namespace passes Pod Security "restricted":
a fixed non-root user, the runtime's default seccomp profile and no Kubernetes API token, which no
pod here needs. Takes the image's own uid and gid.
*/}}
{{- define "ticketrush.podSecurity" -}}
automountServiceAccountToken: false
securityContext:
  runAsNonRoot: true
  runAsUser: {{ .uid }}
  runAsGroup: {{ .gid }}
  fsGroup: {{ .gid }}
  seccompProfile:
    type: RuntimeDefault
{{- end }}

{{/* Container-level security (INF-06): no privilege escalation and no Linux capabilities. */}}
{{- define "ticketrush.containerSecurity" -}}
securityContext:
  allowPrivilegeEscalation: false
  capabilities:
    drop: ["ALL"]
  {{- if .readOnlyRootFilesystem }}
  readOnlyRootFilesystem: true
  {{- end }}
{{- end }}
