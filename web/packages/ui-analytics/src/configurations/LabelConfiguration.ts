import { VisualizationConfiguration } from './VisualizationConfiguration'

export class LabelConfiguration extends VisualizationConfiguration {
  label: string = ''
  fontSize: string = '14px'
  fontWeight: string = '400'
  alignment: string = 'left'

  constructor() {
    super('LABEL')
  }

  loadConfiguration(config: Record<string, unknown>) {
    if (!config) return
    this.label = (config.label as string) || ''
    this.fontSize = (config.fontSize as string) || '14px'
    this.fontWeight = (config.fontWeight as string) || '400'
    this.alignment = (config.alignment as string) || 'left'
  }

  getConfiguration() {
    return {
      label: this.label,
      fontSize: this.fontSize,
      fontWeight: this.fontWeight,
      alignment: this.alignment,
    }
  }

  get isReady() {
    return true
  }
}
