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
