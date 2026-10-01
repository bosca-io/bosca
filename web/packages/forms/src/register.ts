import { registerControl } from './controls'
import TextInput from './components/controls/TextInput.vue'
import TextArea from './components/controls/TextArea.vue'
import NumberInput from './components/controls/NumberInput.vue'
import SelectControl from './components/controls/SelectControl.vue'
import CheckboxControl from './components/controls/CheckboxControl.vue'
import SwitchControl from './components/controls/SwitchControl.vue'
import DatePickerControl from './components/controls/DatePickerControl.vue'
import TagInput from './components/controls/TagInput.vue'
import RadioGroup from './components/controls/RadioGroup.vue'
import ColorPicker from './components/controls/ColorPicker.vue'
import ImageUploadControl from './components/controls/ImageUploadControl.vue'
import FileUploadControl from './components/controls/FileUploadControl.vue'
import GraphqlSelectControl from './components/controls/GraphqlSelectControl.vue'
import ApiScriptSelectControl from './components/controls/ApiScriptSelectControl.vue'
import WorkOpsSelectControl from './components/controls/WorkOpsSelectControl.vue'
import ProfileSelectControl from './components/controls/ProfileSelectControl.vue'

// Data controls
registerControl('text-input', TextInput)
registerControl('textarea', TextArea)
registerControl('number-input', NumberInput)
registerControl('select', SelectControl)
registerControl('checkbox', CheckboxControl)
registerControl('switch', SwitchControl)
registerControl('date-picker', DatePickerControl)
registerControl('tag-input', TagInput)
registerControl('radio-group', RadioGroup)
registerControl('color-picker', ColorPicker)
registerControl('image-upload', ImageUploadControl)
registerControl('file-upload', FileUploadControl)
registerControl('graphql-select', GraphqlSelectControl)
registerControl('api-script-select', ApiScriptSelectControl)
registerControl('workops-select', WorkOpsSelectControl)
registerControl('profile-select', ProfileSelectControl)
