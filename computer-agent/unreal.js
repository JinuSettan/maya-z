const path = require('path');
const fs = require('fs');

function detectUnrealEngine() {
  const possiblePaths = [
    'C:/Program Files/Epic Games/UE_5.4',
    'C:/Program Files/Epic Games/UE_5.3',
    '/Users/Shared/Epic Games/UE_5.4',
    '/opt/unreal-engine/5.4'
  ];
  const found = possiblePaths.find(p => fs.existsSync(p));
  return {
    isInstalled: !!found,
    engineVersion: found ? '5.4' : '5.4',
    installPath: found || 'C:/Program Files/Epic Games/UE_5.4'
  };
}

function createUnrealProject(projectName, folder) {
  const projectDir = path.resolve(folder, projectName);
  fs.mkdirSync(path.join(projectDir, 'Source', projectName), { recursive: true });
  fs.mkdirSync(path.join(projectDir, 'Config'), { recursive: true });

  const uproject = {
    FileVersion: 3,
    EngineAssociation: '5.4',
    Category: '',
    Description: '',
    Modules: [
      {
        Name: projectName,
        Type: 'Runtime',
        LoadingPhase: 'Default'
      }
    ]
  };
  fs.writeFileSync(path.join(projectDir, `${projectName}.uproject`), JSON.stringify(uproject, null, 2), 'utf8');
  return {
    projectName,
    projectPath: projectDir,
    uprojectFilePath: path.join(projectDir, `${projectName}.uproject`)
  };
}

module.exports = {
  detectUnrealEngine,
  createUnrealProject
};
