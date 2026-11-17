{{- define "tf-serving.podTemplate" -}}
metadata:
  labels:
    {{- include "bosca-common.selectorLabels" . | nindent 4 }}
spec:
  serviceAccountName: {{ include "bosca-common.serviceAccountName" . }}
  {{- with .Values.imagePullSecrets }}
  imagePullSecrets:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  securityContext:
    {{- include "bosca-common.podSecurityContext" . | nindent 4 }}
  # Download exact versions before the serving container starts.
  initContainers:
    - name: download-models
      image: {{ include "bosca-common.boscaImage" (dict "image" .Values.loader.image "global" .Values.global) }}
      imagePullPolicy: {{ include "bosca-common.imagePullPolicy" .Values.loader.image }}
      env:
        - name: MODEL_VERSIONS_JSON
          value: "{}"
        - name: MODELS_ROOT
          value: {{ .Values.modelBasePath | quote }}
        - name: ARTIFACTS_URL
          value: {{ .Values.loader.artifactsUrl | quote }}
        - name: ARTIFACTS_NAMESPACE
          value: {{ .Values.loader.artifactsNamespace | default "model" | quote }}
        {{- if .Values.loader.secrets.artifactsToken.enabled }}
        - name: ARTIFACTS_API_TOKEN
          valueFrom:
            secretKeyRef:
              name: {{ .Values.loader.secrets.artifactsToken.name }}
              key: {{ .Values.loader.secrets.artifactsToken.key }}
        {{- end }}
        {{- range $k, $v := .Values.loader.extraEnv }}
        - name: {{ $k }}
          value: {{ $v | quote }}
        {{- end }}
      resources:
        {{- toYaml .Values.loader.resources | nindent 8 }}
      volumeMounts:
        - name: models
          mountPath: {{ .Values.modelBasePath }}
  containers:
    - name: {{ .Chart.Name }}
      image: {{ include "bosca-common.image" (merge (dict "tag" (default .Chart.AppVersion .Values.image.tag)) .Values.image) }}
      imagePullPolicy: {{ include "bosca-common.imagePullPolicy" .Values.image }}
      {{- with .Values.securityContext }}
      securityContext:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      command:
        - tensorflow_model_server
        - --port={{ .Values.service.grpcPort }}
        - --rest_api_port={{ .Values.service.port }}
        - --model_config_file=/config/models.config
        - --model_config_file_poll_wait_seconds=0
        - --file_system_poll_wait_seconds=0
        - --num_load_threads=1
      ports:
        - name: http
          containerPort: {{ .Values.service.port }}
          protocol: TCP
        - name: grpc
          containerPort: {{ .Values.service.grpcPort }}
          protocol: TCP
      envFrom:
        {{- range .Values.envFromConfigMaps }}
        - configMapRef:
            name: {{ . }}
        {{- end }}
      {{- with .Values.extraEnv }}
      env:
        {{- range $k, $v := . }}
        - name: {{ $k }}
          value: {{ $v | quote }}
        {{- end }}
      {{- end }}
      # Make the serving API reachable even before a context model is loaded. Bosca's
      # activation job checks the exact required versions before changing the active model.
      {{- with .Values.livenessProbe }}
      livenessProbe:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.readinessProbe }}
      readinessProbe:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      resources:
        {{- toYaml .Values.resources | nindent 8 }}
      volumeMounts:
        - name: models
          mountPath: {{ .Values.modelBasePath }}
          readOnly: true
        - name: model-config
          mountPath: /config
          readOnly: true
  # The controller injects the generation's model-config ConfigMap volume.
  volumes:
    - name: models
      emptyDir: {}
  {{- with .Values.nodeSelector }}
  nodeSelector:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- with .Values.tolerations }}
  tolerations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- with .Values.affinity }}
  affinity:
    {{- toYaml . | nindent 4 }}
  {{- end }}
{{- end -}}
