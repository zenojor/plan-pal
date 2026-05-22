import fs from 'fs';

const filePath = 'e:/Coding/plan-pal/node_modules/animal-island-ui/dist/es/index.js';
const content = fs.readFileSync(filePath, 'utf-8');

const classIndex = content.indexOf('animal-card-DJ515');
if (classIndex >= 0) {
  // Let's print the next 2000 characters to see the component definition!
  console.log('Found "animal-card-DJ515" at index:', classIndex);
  console.log(content.substring(classIndex, classIndex + 2000));
} else {
  console.log('Could not find class name.');
}
